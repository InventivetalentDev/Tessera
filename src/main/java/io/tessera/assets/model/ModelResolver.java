package io.tessera.assets.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.tessera.assets.fetch.McAssetClient;
import io.tessera.core.BlockKey;
import io.tessera.core.FaceDir;
import org.joml.Quaternionf;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
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
 * <p><b>v1 cube-only:</b> only blocks whose model parent is one of
 * {@link #CUBE_PARENTS} produce a {@link BlockModel}. Anything else returns
 * {@link Optional#empty()} and a one-shot warn-log. Property variants are
 * ignored — we always pick the first variant. Tint blocks (grass_block,
 * oak_leaves, ...) are detected via {@link #TINTED_BLOCKS} and surfaced
 * via {@link BlockModel#tinted()} so the listener can skip them.
 */
public final class ModelResolver {

    /** Model parents that count as a full 1×1×1 cube with one texture per face. */
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

    /**
     * Hardcoded list of vanilla blocks that need biome tint. v1 skips these.
     * Could be derived from {@code tintindex} in elements but our cube
     * parents abstract that away, and the list is small.
     */
    private static final Set<String> TINTED_BLOCKS = Set.of(
            "grass_block", "oak_leaves", "spruce_leaves", "birch_leaves",
            "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves",
            "cherry_leaves", "azalea_leaves", "flowering_azalea_leaves",
            "vine", "water", "bubble_column", "redstone_wire"
    );

    private final McAssetClient client;
    private final Logger logger;
    private final String version;
    private final Map<BlockKey, Optional<BlockModel>> cache = new HashMap<>();

    public ModelResolver(McAssetClient client, Logger logger, String version) {
        this.client = client;
        this.logger = logger;
        this.version = version;
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

            ResolvedModel resolved = resolveModelChain(vs.canonicalModelName);
            if (!CUBE_PARENTS.contains(resolved.terminalParent)) {
                logger.fine("[" + key + "] non-cube parent " + resolved.terminalParent + "; skipping");
                return Optional.empty();
            }

            EnumMap<FaceDir, BufferedImage> faces = pickFaceTextures(key, resolved);
            if (faces.size() != 6) {
                logger.fine("[" + key + "] failed to resolve all 6 faces, got " + faces.keySet());
                return Optional.empty();
            }
            boolean tinted = TINTED_BLOCKS.contains(key.path());
            return Optional.of(new BlockModel(key, faces, tinted, resolved.terminalParent, vs.rotations()));
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
     * Per-variant rotation hints from a blockstate JSON. Carries the raw
     * {@code x}/{@code y} integers (multiples of 90, in degrees) so the
     * values round-trip cleanly through {@code heads.json} without any
     * quaternion-to-Euler ambiguity. Use {@link #toQuat} for the runtime
     * world-space rotation.
     *
     * <p>Vanilla composition order: {@code y} (yaw) is applied after
     * {@code x} (pitch) — i.e. tilt the cube up/down first, then spin
     * around the world Y axis.
     */
    public record VariantRotation(int xDeg, int yDeg) {
        public static final VariantRotation IDENTITY = new VariantRotation(0, 0);

        public Quaternionf toQuat() {
            return new Quaternionf()
                    .rotateY((float) Math.toRadians(yDeg))
                    .rotateX((float) Math.toRadians(xDeg));
        }
    }

    /**
     * Carries the per-block result of parsing a blockstate JSON: which model
     * to bake textures from (canonical = the variant with no x/y rotation
     * hints, falling back to the first one), plus a map from variant key
     * (e.g. {@code "axis=x"}, {@code "facing=west,lit=false"}) to the
     * rotation that variant requires for correct world orientation.
     *
     * <p>Used by {@link #doResolve} to feed both the texture pipeline and
     * the runtime spawn-time rotation lookup. Variant keys mirror vanilla's
     * format (alphabetically sorted, comma-separated). Multipart blockstates
     * collapse to empty since per-state rotation isn't meaningful for them
     * in v1.
     */
    public record BlockstateVariants(String canonicalModelName, Map<String, VariantRotation> rotations) {}

    private static BlockstateVariants parseVariants(JsonObject blockstate) {
        if (blockstate.has("variants")) {
            JsonObject variants = blockstate.getAsJsonObject("variants");
            String canonical = null;
            String firstSeen = null;
            Map<String, VariantRotation> rotations = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : variants.entrySet()) {
                JsonElement v = e.getValue();
                JsonObject obj = v.isJsonArray() ? v.getAsJsonArray().get(0).getAsJsonObject() : v.getAsJsonObject();
                String model = obj.get("model").getAsString();
                int xDeg = obj.has("x") ? obj.get("x").getAsInt() : 0;
                int yDeg = obj.has("y") ? obj.get("y").getAsInt() : 0;
                rotations.put(e.getKey(), new VariantRotation(xDeg, yDeg));
                if (firstSeen == null) firstSeen = model;
                if (canonical == null && xDeg == 0 && yDeg == 0) canonical = model;
            }
            return new BlockstateVariants(canonical != null ? canonical : firstSeen, rotations);
        }
        if (blockstate.has("multipart")) {
            JsonArray arr = blockstate.getAsJsonArray("multipart");
            if (arr.size() > 0) {
                JsonElement applied = arr.get(0).getAsJsonObject().get("apply");
                JsonObject obj = applied.isJsonArray() ? applied.getAsJsonArray().get(0).getAsJsonObject() : applied.getAsJsonObject();
                return new BlockstateVariants(obj.get("model").getAsString(), Collections.emptyMap());
            }
        }
        return new BlockstateVariants(null, Collections.emptyMap());
    }

    private static final class ResolvedModel {
        final String terminalParent;
        final Map<String, String> textureVars;
        ResolvedModel(String terminalParent, Map<String, String> textureVars) {
            this.terminalParent = terminalParent;
            this.textureVars = textureVars;
        }
    }

    /**
     * Walk the parent chain accumulating texture-variable bindings, until a
     * recognized cube parent (or {@code minecraft:block/block}) is hit.
     */
    private ResolvedModel resolveModelChain(String firstModel) throws IOException {
        Map<String, String> tex = new LinkedHashMap<>();
        String current = withDefaultNamespace(firstModel);
        String terminal = null;
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

            if (CUBE_PARENTS.contains(current)) {
                terminal = current;
                break;
            }
            if (!obj.has("parent")) {
                terminal = current;
                break;
            }
            current = withDefaultNamespace(obj.get("parent").getAsString());
        }

        // Resolve indirect references (e.g. "down": "#all") against the bindings.
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : tex.entrySet()) {
            resolved.put(e.getKey(), resolveTextureRef(e.getValue(), tex));
        }
        return new ResolvedModel(terminal, resolved);
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

    private EnumMap<FaceDir, BufferedImage> pickFaceTextures(BlockKey key, ResolvedModel m) throws IOException {
        EnumMap<FaceDir, BufferedImage> out = new EnumMap<>(FaceDir.class);
        Map<String, String> t = m.textureVars;

        // For "cube_all" all 6 faces use #all.
        // For "cube_column" sides=#side, ends=#end.
        // For "cube_bottom_top" top=#top, bottom=#bottom, sides=#side.
        // For full "cube" each face is named.
        String parent = m.terminalParent;
        switch (parent) {
            case "minecraft:block/cube_all", "minecraft:block/cube_mirrored_all" -> {
                BufferedImage all = loadTexture(t.get("all"));
                for (FaceDir d : FaceDir.values()) out.put(d, all);
            }
            case "minecraft:block/cube_column", "minecraft:block/cube_column_horizontal" -> {
                BufferedImage side = loadTexture(t.get("side"));
                BufferedImage end  = loadTexture(t.get("end"));
                out.put(FaceDir.UP,    end);
                out.put(FaceDir.DOWN,  end);
                out.put(FaceDir.NORTH, side);
                out.put(FaceDir.SOUTH, side);
                out.put(FaceDir.EAST,  side);
                out.put(FaceDir.WEST,  side);
            }
            case "minecraft:block/cube_bottom_top" -> {
                BufferedImage top = loadTexture(t.get("top"));
                BufferedImage bot = loadTexture(t.get("bottom"));
                BufferedImage side = loadTexture(t.get("side"));
                out.put(FaceDir.UP,   top);
                out.put(FaceDir.DOWN, bot);
                out.put(FaceDir.NORTH, side); out.put(FaceDir.SOUTH, side);
                out.put(FaceDir.EAST,  side); out.put(FaceDir.WEST,  side);
            }
            case "minecraft:block/cube_top" -> {
                BufferedImage top = loadTexture(t.get("top"));
                BufferedImage side = loadTexture(t.get("side"));
                out.put(FaceDir.UP,   top);
                out.put(FaceDir.DOWN, top);
                out.put(FaceDir.NORTH, side); out.put(FaceDir.SOUTH, side);
                out.put(FaceDir.EAST,  side); out.put(FaceDir.WEST,  side);
            }
            case "minecraft:block/orientable", "minecraft:block/orientable_with_bottom" -> {
                BufferedImage top = loadTexture(t.get("top"));
                BufferedImage bot = parent.endsWith("_with_bottom")
                        ? loadTexture(t.get("bottom")) : top;
                BufferedImage front = loadTexture(t.get("front"));
                BufferedImage side = loadTexture(t.get("side"));
                out.put(FaceDir.UP,   top);
                out.put(FaceDir.DOWN, bot);
                out.put(FaceDir.NORTH, side);
                out.put(FaceDir.SOUTH, front);
                out.put(FaceDir.EAST,  side);
                out.put(FaceDir.WEST,  side);
            }
            case "minecraft:block/cube", "minecraft:block/cube_directional", "minecraft:block/cube_mirrored" -> {
                out.put(FaceDir.UP,    loadTexture(t.getOrDefault("up",    t.get("all"))));
                out.put(FaceDir.DOWN,  loadTexture(t.getOrDefault("down",  t.get("all"))));
                out.put(FaceDir.NORTH, loadTexture(t.getOrDefault("north", t.get("all"))));
                out.put(FaceDir.SOUTH, loadTexture(t.getOrDefault("south", t.get("all"))));
                out.put(FaceDir.EAST,  loadTexture(t.getOrDefault("east",  t.get("all"))));
                out.put(FaceDir.WEST,  loadTexture(t.getOrDefault("west",  t.get("all"))));
            }
            default -> logger.fine("[" + key + "] unsupported cube parent " + parent);
        }
        return out;
    }

    private BufferedImage loadTexture(String ref) throws IOException {
        if (ref == null) throw new IOException("missing texture variable");
        String path = stripNamespace(ref);
        // Block textures live under textures/<path>.png with leading "block/"
        // already part of the path (e.g. "block/stone").
        byte[] png = client.fetch(version, "textures/" + path + ".png");
        BufferedImage full = ImageIO.read(new ByteArrayInputStream(png));
        if (full == null) throw new IOException("failed to decode " + ref);
        // Animated textures are tall (e.g. 16×64 for 4 frames). Take frame 0.
        if (full.getHeight() > full.getWidth()) {
            return full.getSubimage(0, 0, full.getWidth(), full.getWidth());
        }
        return full;
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
