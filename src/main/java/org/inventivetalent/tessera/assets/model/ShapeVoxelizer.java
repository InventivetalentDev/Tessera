package org.inventivetalent.tessera.assets.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.ChunkSpec;
import org.inventivetalent.tessera.core.FaceDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Converts a vanilla model's {@code elements} array into a list of
 * {@link ChunkSpec}s laid out on a {@code gridN}³ lattice — the same
 * primitive {@code TextureSplitter} produces for full-cube blocks. This is
 * the non-cube counterpart that lets slabs, stairs (including corner
 * shapes), and any other multi-element axis-aligned model bake into a
 * sparse, shape-correct chunk lattice.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Parse each element's {@code from}/{@code to} 16-unit bounds and
 *       per-{@link FaceDir} texture references + UV windows (defaulting to
 *       the natural projection of the element onto the face when {@code uv}
 *       is absent).</li>
 *   <li>Voxelize: a grid cell {@code (cx,cy,cz)} is occupied iff its center
 *       — {@code ((cx+0.5)/gridN, (cy+0.5)/gridN, (cz+0.5)/gridN) * 16} —
 *       lies inside any element's bounding box. Build a
 *       {@code Map<ChunkCoord, element-index>} so each cell knows which
 *       element owns it (first containing element wins on overlap, which
 *       is rare in vanilla).</li>
 *   <li>For each occupied cell, compute its outward face set by checking
 *       which of the 6 neighbor cells are unoccupied (out-of-bounds counts
 *       as unoccupied). This is the same rule a full cube produces; for
 *       non-cube shapes it correctly identifies internal boundaries (e.g.
 *       the front of a stair's upper step).</li>
 *   <li>For each outward face, sample the responsible element's face
 *       texture at the cell's element-local position. Axis conventions
 *       match {@code TextureSplitter.sourceTile} so head-skin packing reads
 *       the right tile regardless of which path produced it.</li>
 * </ol>
 *
 * <p><b>Tile size</b>: at the canonical gridN=4 with 16-pixel face textures
 * each cell tile is 4×4 pixels — identical to the cube path. Elements that
 * aren't gridN-aligned (8-unit multiples at gridN=4) produce non-integer
 * pixels-per-cell, which the implementation rounds; for v1 every targeted
 * vanilla shape is grid-aligned so this stays exact.
 *
 * <p><b>DOWN face V-flip</b>: the cube path applies a default V-flip on
 * DOWN (see {@code SourceFlips.DOWN}) because the head model's BOTTOM UV
 * has image-Y = -Z while the tile lookup uses (cx, cz). The voxelizer
 * bakes the same flip in directly so cube and shape paths render DOWN
 * identically.
 *
 * <p>Returned {@link Result#isFullCube} signals when the elements voxelize
 * to a complete N³ cube with uniform face textures — the caller may prefer
 * the cube fast path (which supports biome tint + overlays) over this
 * voxelization output. The voxelizer never tries to detect uniformity
 * beyond cell count; the caller should treat {@code isFullCube=true} as
 * "this could also be a cube" not "this is necessarily uniform."
 */
public final class ShapeVoxelizer {

    public interface TextureLoader {
        BufferedImage load(String textureRef) throws IOException;
    }

    public record Result(List<ChunkSpec> chunks, boolean isFullCube) {}

    private final Logger logger;

    public ShapeVoxelizer(Logger logger) {
        this.logger = logger;
    }

    public Result voxelize(JsonArray elements, int gridN,
                            Map<String, String> textureVars,
                            TextureLoader loader) throws IOException {
        if (gridN < 1) throw new IllegalArgumentException("gridN must be ≥ 1");
        if (elements == null || elements.isEmpty()) {
            return new Result(List.of(), false);
        }

        List<ParsedElement> parsed = parseElements(elements, textureVars);
        if (parsed.isEmpty()) return new Result(List.of(), false);

        // Voxelize: cell -> owning element index. First containing element wins.
        Map<ChunkCoord, Integer> cellOwner = new HashMap<>();
        float cellSize = 16f / gridN;
        for (int cx = 0; cx < gridN; cx++) {
            float xc = (cx + 0.5f) * cellSize;
            for (int cy = 0; cy < gridN; cy++) {
                float yc = (cy + 0.5f) * cellSize;
                for (int cz = 0; cz < gridN; cz++) {
                    float zc = (cz + 0.5f) * cellSize;
                    for (int i = 0; i < parsed.size(); i++) {
                        ParsedElement el = parsed.get(i);
                        if (contains(el, xc, yc, zc)) {
                            cellOwner.put(new ChunkCoord(cx, cy, cz), i);
                            break;
                        }
                    }
                }
            }
        }

        if (cellOwner.isEmpty()) return new Result(List.of(), false);
        boolean isFullCube = cellOwner.size() == gridN * gridN * gridN;

        // Per-element, per-face: pre-load the texture image so we don't
        // reload the same PNG once per cell. textureByElementAndFace caches
        // the cropped + DOWN-flipped face image at the element's UV window.
        Map<Integer, EnumMap<FaceDir, BufferedImage>> faceImageCache = new HashMap<>();

        // Emit per-cell outward tiles.
        List<ChunkSpec> out = new ArrayList<>();
        for (Map.Entry<ChunkCoord, Integer> e : cellOwner.entrySet()) {
            ChunkCoord coord = e.getKey();
            int ownerIdx = e.getValue();
            ParsedElement owner = parsed.get(ownerIdx);

            EnumMap<FaceDir, BufferedImage> outwardTiles = new EnumMap<>(FaceDir.class);
            for (FaceDir d : FaceDir.values()) {
                if (!isOutward(coord, d, cellOwner, gridN)) continue;
                ParsedFace face = owner.faces.get(d);
                if (face == null) {
                    // Element omits this face direction (e.g. cullface
                    // optimization where vanilla doesn't render it because
                    // it would always be hidden). For us this corresponds
                    // to a face that should be rendered (the cell IS on the
                    // shape boundary in this direction) but the model has
                    // no texture for it. Skip — the chunk just won't show
                    // anything on that face, which usually means the
                    // chunk's adjacent cell was meant to cover it. Log
                    // once at fine.
                    logger.fine("[voxelize] cell " + coord + " outward " + d
                            + " but element " + ownerIdx + " has no face data");
                    continue;
                }
                BufferedImage faceImg = faceImageCache
                        .computeIfAbsent(ownerIdx, k -> new EnumMap<>(FaceDir.class))
                        .computeIfAbsent(d, dd -> loadFaceImage(owner, dd, loader));
                if (faceImg == null) continue;
                BufferedImage tile = sampleTile(faceImg, owner, d, coord, gridN);
                outwardTiles.put(d, tile);
            }

            if (!outwardTiles.isEmpty()) {
                out.add(new ChunkSpec(coord, outwardTiles));
            }
        }

        return new Result(out, isFullCube);
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    private record ParsedElement(
            float fx, float fy, float fz, float tx, float ty, float tz,
            Map<FaceDir, ParsedFace> faces) {}

    private record ParsedFace(String textureRef, int u1, int v1, int u2, int v2) {}

    private List<ParsedElement> parseElements(JsonArray elements, Map<String, String> textureVars) {
        List<ParsedElement> out = new ArrayList<>(elements.size());
        for (JsonElement je : elements) {
            JsonObject elem = je.getAsJsonObject();
            if (!elem.has("from") || !elem.has("to")) continue;
            JsonArray from = elem.getAsJsonArray("from");
            JsonArray to = elem.getAsJsonArray("to");
            float fx = from.get(0).getAsFloat();
            float fy = from.get(1).getAsFloat();
            float fz = from.get(2).getAsFloat();
            float tx = to.get(0).getAsFloat();
            float ty = to.get(1).getAsFloat();
            float tz = to.get(2).getAsFloat();

            Map<FaceDir, ParsedFace> faces = new EnumMap<>(FaceDir.class);
            if (elem.has("faces")) {
                JsonObject facesJson = elem.getAsJsonObject("faces");
                for (FaceDir d : FaceDir.values()) {
                    String jsonName = d.jsonName();
                    if (!facesJson.has(jsonName)) continue;
                    JsonObject f = facesJson.getAsJsonObject(jsonName);
                    if (!f.has("texture")) continue;
                    String texRef = resolveTextureRef(f.get("texture").getAsString(), textureVars);
                    int[] uv = parseUv(f, d, fx, fy, fz, tx, ty, tz);
                    faces.put(d, new ParsedFace(texRef, uv[0], uv[1], uv[2], uv[3]));
                }
            }
            out.add(new ParsedElement(fx, fy, fz, tx, ty, tz, faces));
        }
        return out;
    }

    /**
     * Resolve {@code "#var"} → texture path against the model's bindings.
     * Mirrors {@code ModelResolver.resolveTextureRef}.
     */
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

    /**
     * Parse the {@code uv} array for a face, or compute the vanilla default
     * from the element's bounds projected onto the face plane. Result is
     * always {@code [u1, v1, u2, v2]} in 16-unit image coordinates with
     * {@code u1 ≤ u2} and {@code v1 ≤ v2}.
     *
     * <p>Defaults (vanilla {@code BlockElementFace.defaultFaceUV}):
     * <ul>
     *   <li>UP:    [fx, fz, tx, tz]</li>
     *   <li>DOWN:  [fx, 16-tz, tx, 16-fz]</li>
     *   <li>NORTH: [16-tx, 16-ty, 16-fx, 16-fy]</li>
     *   <li>SOUTH: [fx, 16-ty, tx, 16-fy]</li>
     *   <li>EAST:  [16-tz, 16-ty, 16-fz, 16-fy]</li>
     *   <li>WEST:  [fz, 16-ty, tz, 16-fy]</li>
     * </ul>
     */
    private static int[] parseUv(JsonObject face, FaceDir d,
                                  float fx, float fy, float fz, float tx, float ty, float tz) {
        if (face.has("uv")) {
            JsonArray uv = face.getAsJsonArray("uv");
            int u1 = uv.get(0).getAsInt();
            int v1 = uv.get(1).getAsInt();
            int u2 = uv.get(2).getAsInt();
            int v2 = uv.get(3).getAsInt();
            // Normalize so u1 ≤ u2, v1 ≤ v2 (vanilla allows reversed UVs to
            // signal mirroring; we approximate by un-mirroring, which is good
            // enough for slabs/stairs).
            if (u1 > u2) { int t = u1; u1 = u2; u2 = t; }
            if (v1 > v2) { int t = v1; v1 = v2; v2 = t; }
            return new int[] { u1, v1, u2, v2 };
        }
        int u1, v1, u2, v2;
        switch (d) {
            case UP:    u1 = (int) fx; v1 = (int) fz;       u2 = (int) tx; v2 = (int) tz;       break;
            case DOWN:  u1 = (int) fx; v1 = (int) (16 - tz); u2 = (int) tx; v2 = (int) (16 - fz); break;
            case NORTH: u1 = (int) (16 - tx); v1 = (int) (16 - ty); u2 = (int) (16 - fx); v2 = (int) (16 - fy); break;
            case SOUTH: u1 = (int) fx; v1 = (int) (16 - ty); u2 = (int) tx; v2 = (int) (16 - fy); break;
            case EAST:  u1 = (int) (16 - tz); v1 = (int) (16 - ty); u2 = (int) (16 - fz); v2 = (int) (16 - fy); break;
            case WEST:  u1 = (int) fz; v1 = (int) (16 - ty); u2 = (int) tz; v2 = (int) (16 - fy); break;
            default: throw new IllegalStateException();
        }
        return new int[] { u1, v1, u2, v2 };
    }

    // ── Voxelization helpers ─────────────────────────────────────────────────

    private static boolean contains(ParsedElement el, float xc, float yc, float zc) {
        return xc >= el.fx && xc <= el.tx
            && yc >= el.fy && yc <= el.ty
            && zc >= el.fz && zc <= el.tz;
    }

    private static boolean isOutward(ChunkCoord c, FaceDir d,
                                      Map<ChunkCoord, Integer> cellOwner, int gridN) {
        int nx = c.x() + d.dx, ny = c.y() + d.dy, nz = c.z() + d.dz;
        if (nx < 0 || nx >= gridN || ny < 0 || ny >= gridN || nz < 0 || nz >= gridN) return true;
        return !cellOwner.containsKey(new ChunkCoord(nx, ny, nz));
    }

    // ── Texture sampling ─────────────────────────────────────────────────────

    /**
     * Load the texture, crop to the element's UV window, and apply the
     * face-specific orientation defaults (DOWN gets a V-flip — see class
     * javadoc).
     */
    private BufferedImage loadFaceImage(ParsedElement el, FaceDir d, TextureLoader loader) {
        ParsedFace face = el.faces.get(d);
        if (face == null) return null;
        BufferedImage full;
        try {
            full = loader.load(face.textureRef);
        } catch (IOException io) {
            logger.fine("[voxelize] failed to load texture " + face.textureRef + ": " + io.getMessage());
            return null;
        }
        if (full == null) return null;

        // Map UV (in 16-unit image coords) to pixel coords on the actual
        // texture (which may be 16×16 or higher resolution).
        int texW = full.getWidth();
        int texH = full.getHeight();
        int px1 = Math.round(face.u1 * (texW / 16f));
        int py1 = Math.round(face.v1 * (texH / 16f));
        int px2 = Math.round(face.u2 * (texW / 16f));
        int py2 = Math.round(face.v2 * (texH / 16f));
        if (px2 <= px1 || py2 <= py1) return null;
        BufferedImage cropped = full.getSubimage(px1, py1, px2 - px1, py2 - py1);

        if (d == FaceDir.DOWN) {
            // Bake the DOWN V-flip permanently into the shape path —
            // matches SourceFlips.DOWN default for the cube path.
            return flipV(cropped);
        }
        return cropped;
    }

    /**
     * Compute the cell's tile within the cropped face image. Axis
     * conventions mirror {@code TextureSplitter.sourceTile} but with
     * element-local indices.
     */
    private static BufferedImage sampleTile(BufferedImage faceImg, ParsedElement el,
                                             FaceDir d, ChunkCoord coord, int gridN) {
        // Cell range covered by the element on each axis.
        int cellFromX = (int) Math.floor(el.fx * gridN / 16f);
        int cellFromY = (int) Math.floor(el.fy * gridN / 16f);
        int cellFromZ = (int) Math.floor(el.fz * gridN / 16f);
        int cellToX = (int) Math.ceil(el.tx * gridN / 16f);
        int cellToY = (int) Math.ceil(el.ty * gridN / 16f);
        int cellToZ = (int) Math.ceil(el.tz * gridN / 16f);
        int cellsX = Math.max(1, cellToX - cellFromX);
        int cellsY = Math.max(1, cellToY - cellFromY);
        int cellsZ = Math.max(1, cellToZ - cellFromZ);

        int localX = coord.x() - cellFromX;
        int localY = coord.y() - cellFromY;
        int localZ = coord.z() - cellFromZ;

        int faceU, faceV, faceCellsU, faceCellsV;
        switch (d) {
            case UP:
                faceU = localX;
                faceV = localZ;
                faceCellsU = cellsX;
                faceCellsV = cellsZ;
                break;
            case DOWN:
                // Same lookup as UP — the DOWN V-flip baked into faceImg
                // takes care of the head-model convention.
                faceU = localX;
                faceV = localZ;
                faceCellsU = cellsX;
                faceCellsV = cellsZ;
                break;
            case NORTH:
                faceU = (cellsX - 1) - localX;
                faceV = (cellsY - 1) - localY;
                faceCellsU = cellsX;
                faceCellsV = cellsY;
                break;
            case SOUTH:
                faceU = localX;
                faceV = (cellsY - 1) - localY;
                faceCellsU = cellsX;
                faceCellsV = cellsY;
                break;
            case EAST:
                faceU = (cellsZ - 1) - localZ;
                faceV = (cellsY - 1) - localY;
                faceCellsU = cellsZ;
                faceCellsV = cellsY;
                break;
            case WEST:
                faceU = localZ;
                faceV = (cellsY - 1) - localY;
                faceCellsU = cellsZ;
                faceCellsV = cellsY;
                break;
            default:
                throw new IllegalStateException();
        }

        int tileW = Math.max(1, faceImg.getWidth() / faceCellsU);
        int tileH = Math.max(1, faceImg.getHeight() / faceCellsV);
        int x0 = Math.min(faceImg.getWidth() - tileW, faceU * tileW);
        int y0 = Math.min(faceImg.getHeight() - tileH, faceV * tileH);
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        return faceImg.getSubimage(x0, y0, tileW, tileH);
    }

    private static BufferedImage flipV(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, h - 1 - y, src.getRGB(x, y));
            }
        }
        return out;
    }

    // For tests / diagnostic — expose voxelization without texture sampling.
    public static Map<ChunkCoord, Integer> voxelizeCellsOnly(JsonArray elements, int gridN) {
        Map<ChunkCoord, Integer> cellOwner = new LinkedHashMap<>();
        float cellSize = 16f / gridN;
        List<float[]> bounds = new ArrayList<>();
        for (JsonElement je : elements) {
            JsonObject elem = je.getAsJsonObject();
            if (!elem.has("from") || !elem.has("to")) continue;
            JsonArray f = elem.getAsJsonArray("from");
            JsonArray t = elem.getAsJsonArray("to");
            bounds.add(new float[] {
                    f.get(0).getAsFloat(), f.get(1).getAsFloat(), f.get(2).getAsFloat(),
                    t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat() });
        }
        for (int cx = 0; cx < gridN; cx++) {
            float xc = (cx + 0.5f) * cellSize;
            for (int cy = 0; cy < gridN; cy++) {
                float yc = (cy + 0.5f) * cellSize;
                for (int cz = 0; cz < gridN; cz++) {
                    float zc = (cz + 0.5f) * cellSize;
                    for (int i = 0; i < bounds.size(); i++) {
                        float[] b = bounds.get(i);
                        if (xc >= b[0] && xc <= b[3] && yc >= b[1] && yc <= b[4] && zc >= b[2] && zc <= b[5]) {
                            cellOwner.put(new ChunkCoord(cx, cy, cz), i);
                            break;
                        }
                    }
                }
            }
        }
        return cellOwner;
    }
}
