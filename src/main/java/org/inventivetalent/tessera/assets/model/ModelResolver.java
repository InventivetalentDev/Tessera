package org.inventivetalent.tessera.assets.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.inventivetalent.tessera.assets.fetch.McAssetClient;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.FaceDir;
import org.joml.Quaternionf;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves a {@link BlockKey} to a {@link BlockModel} by walking the vanilla
 * blockstate → model parent chain on mcasset.cloud and downloading the
 * referenced textures.
 *
 * <p>Output model can hold multiple shapes (one per distinct model path the
 * blockstate references) plus a variant → (shape, rotation) lookup table.
 * Stairs end up with three shapes (straight, inner, outer corners); slabs
 * + cubes with one. Each shape is either:
 * <ul>
 *   <li>{@link ShapeContent.CubeFaces} — when all elements are full cubes
 *       ({@code from=[0,0,0] to=[16,16,16]}). The classic Tessera path,
 *       supporting biome tint + tinted overlays (grass_block, leaves).</li>
 *   <li>{@link ShapeContent.VoxelizedShape} — when the model has any
 *       non-cube element. The {@link ShapeVoxelizer} cuts it into chunks
 *       directly. No tint / overlay support on this path in v1.</li>
 * </ul>
 *
 * <p>Tint detection is driven by {@code tintindex} in the model JSON (not
 * a hardcoded list), and only cube-faces shapes can be tinted.
 */
public final class ModelResolver {

    /**
     * Model parents that resolve treats as "the cube terminal" purely for
     * informational logging ({@link BlockModel#parentChain}). The actual
     * cube-vs-voxelize decision happens by inspecting whether every element
     * is a full cube, not by parent name.
     */
    private static final Set<String> CUBE_PARENTS = Set.of(
            "minecraft:block/cube",
            "minecraft:block/cube_all",
            "minecraft:block/cube_column",
            "minecraft:block/cube_column_horizontal",
            "minecraft:block/cube_bottom_top",
            "minecraft:block/cube_top",
            "minecraft:block/cube_mirrored",
            "minecraft:block/cube_mirrored_all",
            "minecraft:block/cube_directional",
            "minecraft:block/orientable",
            "minecraft:block/orientable_with_bottom"
    );

    private final McAssetClient client;
    private final Logger logger;
    private final String version;
    private final int gridN;
    private final ShapeVoxelizer voxelizer;
    private final Map<BlockKey, Optional<BlockModel>> cache = new HashMap<>();

    public ModelResolver(McAssetClient client, Logger logger, String version, int gridN) {
        this.client = client;
        this.logger = logger;
        this.version = version;
        this.gridN = gridN;
        this.voxelizer = new ShapeVoxelizer(logger);
    }

    public Optional<BlockModel> resolve(BlockKey key) {
        return cache.computeIfAbsent(key, this::doResolve);
    }

    private Optional<BlockModel> doResolve(BlockKey key) {
        try {
            JsonObject blockstate = JsonParser.parseString(
                    client.fetchString(version, "blockstates/" + key.path() + ".json"))
                    .getAsJsonObject();
            BlockstateVariants vs = parseVariants(blockstate);
            if (vs.canonicalModelName == null) {
                logger.fine("[" + key + "] blockstate has no usable variant; skipping");
                return Optional.empty();
            }

            // Enumerate distinct model paths referenced by any variant.
            // Resolve each in turn — they share the model-chain logic and
            // texture loader.
            LinkedHashMap<String, ShapeContent> shapes = new LinkedHashMap<>();
            String terminalParent = null;
            for (String modelPath : distinctModels(vs)) {
                ResolvedModel resolved = resolveModelChain(modelPath);
                if (terminalParent == null) terminalParent = resolved.terminalParent;

                ShapeContent content = resolveShape(key, resolved);
                if (content == null) {
                    logger.fine("[" + key + "] model " + modelPath + " failed to resolve a shape");
                    continue;
                }
                shapes.put(modelPath, content);
            }

            if (shapes.isEmpty()) {
                logger.fine("[" + key + "] no resolvable shapes");
                return Optional.empty();
            }

            // Drop block if any shape has transparent pixels (player-head skins
            // don't support partial transparency).
            if (anyShapeHasTransparency(shapes)) {
                logger.fine("[" + key + "] skipping — face textures contain transparent pixels");
                return Optional.empty();
            }

            String defaultShapeKey = shapes.containsKey(vs.canonicalModelName)
                    ? vs.canonicalModelName
                    : shapes.keySet().iterator().next();

            // Variant bindings: (shapeKey, rotation). If a variant pointed
            // at a model we couldn't resolve, fall back to the default
            // shape so the variant key still has a usable binding.
            LinkedHashMap<String, BlockModel.VariantBinding> variants = new LinkedHashMap<>();
            for (Map.Entry<String, VariantEntry> e : vs.entries.entrySet()) {
                VariantEntry ve = e.getValue();
                String shapeKey = shapes.containsKey(ve.modelPath) ? ve.modelPath : defaultShapeKey;
                variants.put(e.getKey(), new BlockModel.VariantBinding(
                        shapeKey, new VariantRotation(ve.xDeg, ve.yDeg)));
            }

            return Optional.of(new BlockModel(key, defaultShapeKey, shapes, variants, terminalParent));
        } catch (McAssetClient.AssetNotFoundException missing) {
            logger.fine("[" + key + "] no blockstate on mcasset.cloud (" + missing.getMessage() + ")");
            return Optional.empty();
        } catch (IOException io) {
            logger.log(Level.WARNING, "[" + key + "] I/O error resolving model", io);
            return Optional.empty();
        } catch (RuntimeException re) {
            logger.log(Level.WARNING, "[" + key + "] unexpected error resolving model", re);
            return Optional.empty();
        }
    }

    /**
     * Decide whether a resolved model is a full-cube (potentially tinted +
     * overlaid) or needs voxelizing. The discriminator is: every
     * {@code elements} entry must be {@code from=[0,0,0] to=[16,16,16]} for
     * the cube path. As soon as any element has different bounds, the
     * voxelizer takes over.
     */
    private ShapeContent resolveShape(BlockKey key, ResolvedModel resolved) throws IOException {
        if (resolved.elements == null || resolved.elements.isEmpty()) {
            logger.fine("[" + key + "] model has no elements; skipping");
            return null;
        }
        boolean allFullCube = true;
        for (JsonElement el : resolved.elements) {
            if (!isFullCubeBounds(el.getAsJsonObject())) { allFullCube = false; break; }
        }
        if (allFullCube) {
            CubeFaceData fd = pickFaceTextures(key, resolved);
            if (fd.bases.size() != 6) {
                logger.fine("[" + key + "] cube path: failed to resolve all 6 faces, got " + fd.bases.keySet());
                return null;
            }
            return new ShapeContent.CubeFaces(fd.bases, fd.tinted, fd.overlays);
        }
        // Voxelize. Use the model chain's resolved texture bindings so
        // {@code "#side"} → texture path lookups work.
        ShapeVoxelizer.TextureLoader loader = this::loadTexture;
        ShapeVoxelizer.Result vr = voxelizer.voxelize(resolved.elements, gridN,
                resolved.textureVars, loader);
        if (vr.chunks().isEmpty()) {
            logger.fine("[" + key + "] voxelizer produced no chunks");
            return null;
        }
        return new ShapeContent.VoxelizedShape(vr.chunks());
    }

    private static boolean anyShapeHasTransparency(Map<String, ShapeContent> shapes) {
        for (ShapeContent sc : shapes.values()) {
            if (sc instanceof ShapeContent.CubeFaces cf && hasTransparentPixels(cf.faces())) return true;
            // VoxelizedShape tiles are sliced from non-transparent vanilla
            // block textures; the cube-faces transparency rule mostly
            // exists to skip glass/leaves at the cube path. Re-check would
            // be expensive (per-tile scan) so we accept the risk.
        }
        return false;
    }

    /**
     * Per-variant rotation hints from a blockstate JSON. Carries the raw
     * {@code x}/{@code y} integers (multiples of 90, in degrees) so the
     * values round-trip cleanly through {@code heads.json} without any
     * quaternion-to-Euler ambiguity. Use {@link #toQuat} for the runtime
     * world-space rotation.
     *
     * <p>Vanilla composition order: {@code y} (yaw) is applied after
     * {@code x} (pitch) — i.e. tilt the cube up/down first, then spin
     * around the world Y axis.
     *
     * <p><b>Y sign:</b> vanilla blockstate's {@code y} rotates clockwise as
     * viewed from above (so {@code y=90} maps the model's {@code -Z} face to
     * world {@code +X} — north→east, which matches what
     * {@code facing=east} variants expect). JOML's {@code rotateY} is the
     * standard right-hand rule (counter-clockwise from above), so we negate
     * the angle here.
     */
    public record VariantRotation(int xDeg, int yDeg) {
        public Quaternionf toQuat() {
            return new Quaternionf()
                    .rotateY((float) Math.toRadians(-yDeg))
                    .rotateX((float) Math.toRadians(xDeg));
        }
    }

    /**
     * Per-blockstate result of {@link #parseVariants}: which model is
     * "canonical" (the variant with no x/y rotation hints — preferred as
     * the default shape key) and the full table of variant key → (model
     * path, rotation). Multipart blockstates collapse to one canonical
     * entry — variant-driven shape selection isn't meaningful for them in
     * v1.
     */
    public record BlockstateVariants(String canonicalModelName, Map<String, VariantEntry> entries) {}

    /**
     * One blockstate variant entry: which model file it uses + the runtime
     * rotation. Model paths are normalized to include the {@code minecraft:}
     * namespace.
     */
    public record VariantEntry(String modelPath, int xDeg, int yDeg) {}

    private static BlockstateVariants parseVariants(JsonObject blockstate) {
        if (blockstate.has("variants")) {
            JsonObject variants = blockstate.getAsJsonObject("variants");
            String canonical = null;
            String firstSeen = null;
            Map<String, VariantEntry> entries = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
                JsonElement v = e.getValue();
                JsonObject obj = v.isJsonArray() ? v.getAsJsonArray().get(0).getAsJsonObject() : v.getAsJsonObject();
                String model = withDefaultNamespace(obj.get("model").getAsString());
                int xDeg = obj.has("x") ? obj.get("x").getAsInt() : 0;
                int yDeg = obj.has("y") ? obj.get("y").getAsInt() : 0;
                entries.put(e.getKey(), new VariantEntry(model, xDeg, yDeg));
                if (firstSeen == null) firstSeen = model;
                if (canonical == null && xDeg == 0 && yDeg == 0) canonical = model;
            }
            return new BlockstateVariants(canonical != null ? canonical : firstSeen, entries);
        }
        if (blockstate.has("multipart")) {
            JsonArray arr = blockstate.getAsJsonArray("multipart");
            if (!arr.isEmpty()) {
                JsonElement applied = arr.get(0).getAsJsonObject().get("apply");
                JsonObject obj = applied.isJsonArray() ? applied.getAsJsonArray().get(0).getAsJsonObject() : applied.getAsJsonObject();
                return new BlockstateVariants(withDefaultNamespace(obj.get("model").getAsString()),
                        Collections.emptyMap());
            }
        }
        return new BlockstateVariants(null, Collections.emptyMap());
    }

    private static java.util.Set<String> distinctModels(BlockstateVariants vs) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (vs.canonicalModelName != null) set.add(vs.canonicalModelName);
        for (VariantEntry e : vs.entries.values()) set.add(e.modelPath);
        return set;
    }

    private static final class ResolvedModel {
        final String terminalParent;
        final Map<String, String> textureVars;
        final JsonArray elements;
        ResolvedModel(String terminalParent, Map<String, String> textureVars, JsonArray elements) {
            this.terminalParent = terminalParent;
            this.textureVars = textureVars;
            this.elements = elements;
        }
    }

    /**
     * Walk the parent chain to the root, accumulating texture-variable bindings
     * (child-first wins) and capturing the first {@code elements} array seen
     * (vanilla overrides elements wholesale, so first-seen wins). The terminal
     * parent is the first {@link #CUBE_PARENTS} entry encountered (used only
     * for informational {@link BlockModel#parentChain}; the cube-vs-voxel
     * decision is data-driven, not name-driven).
     */
    private ResolvedModel resolveModelChain(String firstModel) throws IOException {
        Map<String, String> tex = new LinkedHashMap<>();
        String current = withDefaultNamespace(firstModel);
        String terminal = null;
        JsonArray elements = null;
        int hops = 0;

        while (hops++ < 16) {
            String path = stripNamespace(current);
            JsonObject obj = JsonParser.parseString(
                    client.fetchString(version, "models/" + path + ".json"))
                    .getAsJsonObject();

            if (obj.has("textures")) {
                for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("textures").entrySet()) {
                    // Don't overwrite — child models override parents, and
                    // we walk child-first.
                    tex.putIfAbsent(e.getKey(), e.getValue().getAsString());
                }
            }

            if (elements == null && obj.has("elements")) {
                elements = obj.getAsJsonArray("elements");
            }

            if (terminal == null && CUBE_PARENTS.contains(current)) {
                terminal = current;
            }

            if (!obj.has("parent")) {
                if (terminal == null) terminal = current;
                break;
            }
            current = withDefaultNamespace(obj.get("parent").getAsString());
        }

        // Resolve indirect references (e.g. "down": "#all") against the bindings.
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : tex.entrySet()) {
            resolved.put(e.getKey(), resolveTextureRef(e.getValue(), tex));
        }
        return new ResolvedModel(terminal, resolved, elements);
    }

    private static String resolveTextureRef(String value, Map<String, String> bindings) {
        String s = value;
        int hops = 0;
        while (s.startsWith("#") && hops++ < 8) {
            String next = bindings.get(s.substring(1));
            if (next == null) break;
            s = next;
        }
        return s;
    }

    private record CubeFaceData(
            EnumMap<FaceDir, BufferedImage> bases,
            EnumSet<FaceDir> tinted,
            EnumMap<FaceDir, BufferedImage> overlays) {}

    /**
     * Extract per-face base textures, tintindex flags, and overlay textures
     * from a model that's been determined to be cube-shaped. Identical to
     * the pre-multishape logic.
     *
     * <p>Pass 1 — first full-cube element: loads the 6 base textures and
     * records which faces carry {@code "tintindex": 0} (i.e. should be
     * multiplied by the biome color at bake time).
     *
     * <p>Pass 2 — any additional full-cube elements: treated as alpha-composited
     * overlays. Vanilla grass_block uses this for its tinted side overlay
     * ({@code grass_block_side_overlay.png}). Each face of the overlay element
     * that has a {@code tintindex} is collected; at bake time it is tinted then
     * composited on top of the base face, reproducing vanilla's two-layer render.
     */
    private CubeFaceData pickFaceTextures(BlockKey key, ResolvedModel m) throws IOException {
        EnumMap<FaceDir, BufferedImage> bases = new EnumMap<>(FaceDir.class);
        EnumSet<FaceDir> tinted = EnumSet.noneOf(FaceDir.class);
        EnumMap<FaceDir, BufferedImage> overlays = new EnumMap<>(FaceDir.class);
        if (m.elements == null) return new CubeFaceData(bases, tinted, overlays);

        boolean foundBase = false;
        for (JsonElement el : m.elements) {
            JsonObject elem = el.getAsJsonObject();
            if (!isFullCubeBounds(elem) || !elem.has("faces")) continue;
            JsonObject facesJson = elem.getAsJsonObject("faces");

            if (!foundBase) {
                for (FaceDir d : FaceDir.values()) {
                    String jsonName = d.jsonName();
                    if (!facesJson.has(jsonName)) {
                        logger.fine("[" + key + "] face " + jsonName + " missing from cube element");
                        return new CubeFaceData(new EnumMap<>(FaceDir.class),
                                EnumSet.noneOf(FaceDir.class), new EnumMap<>(FaceDir.class));
                    }
                    JsonObject faceObj = facesJson.getAsJsonObject(jsonName);
                    String ref = faceObj.get("texture").getAsString();
                    bases.put(d, loadTexture(resolveTextureRef(ref, m.textureVars)));
                    if (faceObj.has("tintindex") && faceObj.get("tintindex").getAsInt() >= 0) {
                        tinted.add(d);
                    }
                }
                foundBase = true;
            } else {
                for (FaceDir d : FaceDir.values()) {
                    String jsonName = d.jsonName();
                    if (!facesJson.has(jsonName)) continue;
                    JsonObject faceObj = facesJson.getAsJsonObject(jsonName);
                    if (!faceObj.has("tintindex") || faceObj.get("tintindex").getAsInt() < 0) continue;
                    String ref = faceObj.get("texture").getAsString();
                    overlays.put(d, loadTexture(resolveTextureRef(ref, m.textureVars)));
                }
            }
        }

        if (!foundBase) {
            logger.fine("[" + key + "] no full-cube element with faces; skipping");
        }
        return new CubeFaceData(bases, tinted, overlays);
    }

    private static boolean hasTransparentPixels(Map<FaceDir, BufferedImage> faces) {
        for (BufferedImage img : faces.values()) {
            int w = img.getWidth();
            int h = img.getHeight();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (((img.getRGB(x, y) >>> 24) & 0xFF) < 255) return true;
                }
            }
        }
        return false;
    }

    private static boolean isFullCubeBounds(JsonObject element) {
        if (!element.has("from") || !element.has("to")) return false;
        JsonArray from = element.getAsJsonArray("from");
        JsonArray to = element.getAsJsonArray("to");
        return from.get(0).getAsFloat() == 0f && from.get(1).getAsFloat() == 0f && from.get(2).getAsFloat() == 0f
            && to.get(0).getAsFloat() == 16f && to.get(1).getAsFloat() == 16f && to.get(2).getAsFloat() == 16f;
    }

    private BufferedImage loadTexture(String ref) throws IOException {
        if (ref == null) throw new IOException("missing texture variable");
        String path = stripNamespace(ref);
        byte[] png = client.fetch(version, "textures/" + path + ".png");
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stripColorChunks(png)));
        if (decoded == null) throw new IOException("failed to decode " + ref);
        BufferedImage full = normalizeToArgb(decoded);
        // Animated textures are tall (e.g. 16×64 for 4 frames). Take frame 0.
        if (full.getHeight() > full.getWidth()) {
            return full.getSubimage(0, 0, full.getWidth(), full.getWidth());
        }
        return full;
    }

    /**
     * Strips {@code gAMA}, {@code sRGB}, {@code iCCP}, and {@code cHRM} chunks
     * from a PNG byte array before passing it to {@link ImageIO#read}.
     *
     * <p>Java's ImageIO honours these chunks and applies gamma / colour-profile
     * corrections when decoding. Vanilla Minecraft block textures (e.g.
     * {@code stone.png}) ship with a {@code gAMA=100000} chunk that marks the
     * data as linear-light; ImageIO responds by encoding it to sRGB display
     * space, boosting mid-range grey 126 → ~182 in the resulting
     * {@link BufferedImage}. That inflated value is then baked into our player-
     * head skin and rendered ~46 % too bright in-game. Stripping the metadata
     * tells ImageIO to treat the bytes as plain sRGB (the same assumption the
     * Minecraft block-texture atlas loader uses), preserving exact pixel values.
     */
    private static byte[] stripColorChunks(byte[] png) {
        if (png.length < 8) return png;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(png.length);
            out.write(png, 0, 8); // PNG signature
            int pos = 8;
            while (pos + 12 <= png.length) {
                int len = ((png[pos]   & 0xFF) << 24) | ((png[pos+1] & 0xFF) << 16)
                        | ((png[pos+2] & 0xFF) <<  8) |  (png[pos+3] & 0xFF);
                if (len < 0 || pos + 12 + len > png.length) break; // malformed
                String type = new String(png, pos + 4, 4, StandardCharsets.US_ASCII);
                boolean strip = type.equals("gAMA") || type.equals("sRGB")
                             || type.equals("iCCP") || type.equals("cHRM");
                if (!strip) out.write(png, pos, 12 + len);
                pos += 12 + len;
            }
            return out.toByteArray();
        } catch (Exception e) {
            return png; // ByteArrayOutputStream never throws; satisfy javac
        }
    }

    /**
     * Ensures the decoded image is {@link BufferedImage#TYPE_INT_ARGB} with raw
     * pixel values unchanged. Necessary for grayscale PNGs (vanilla stone.png
     * is one) so {@code getRGB} doesn't apply a gamma encode.
     */
    private static BufferedImage normalizeToArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        if (src.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_GRAY) {
            Raster raster = src.getRaster();
            boolean hasAlpha = src.getColorModel().hasAlpha();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int v = raster.getSample(x, y, 0);
                    int a = hasAlpha ? raster.getSample(x, y, 1) : 255;
                    dst.setRGB(x, y, (a << 24) | (v << 16) | (v << 8) | v);
                }
            }
        } else {
            Graphics2D g = dst.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
        }
        return dst;
    }

    private static String withDefaultNamespace(String s) {
        String low = s.toLowerCase(Locale.ROOT);
        return low.contains(":") ? low : "minecraft:" + low;
    }

    private static String stripNamespace(String s) {
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }
}
