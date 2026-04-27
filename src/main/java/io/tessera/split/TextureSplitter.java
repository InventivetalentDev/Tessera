package io.tessera.split;

import io.tessera.assets.model.BlockModel;
import io.tessera.core.ChunkCoord;
import io.tessera.core.ChunkSpec;
import io.tessera.core.FaceDir;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Slices a {@link BlockModel}'s 6 face textures into N×N tiles and emits one
 * {@link ChunkSpec} per visible chunk in the gridN³ volume.
 *
 * <p>Coordinate mapping (the tricky bit): Minecraft block-space is right-handed
 * with +X east, +Y up, +Z south. Vanilla face textures are 16×16 PNGs whose
 * U axis (image X) and V axis (image Y) are oriented per-face. For our
 * sub-block split we want the chunk at grid coord {@code (cx,cy,cz)} to
 * sample the (sx,sy) tile of each outward face such that the image lines up
 * across adjacent chunks — i.e. neighbouring chunks' tiles are pixel-adjacent.
 *
 * <p>Per-face source-tile mapping (matches the standard vanilla face UV
 * conventions; verified empirically in step 11 of the plan):
 * <ul>
 *   <li>UP    (+Y) — tileX = cx,             tileY = cz</li>
 *   <li>DOWN  (-Y) — tileX = cx,             tileY = cz</li>
 *   <li>NORTH (-Z) — tileX = (N-1) − cx,     tileY = (N-1) − cy</li>
 *   <li>SOUTH (+Z) — tileX = cx,             tileY = (N-1) − cy</li>
 *   <li>EAST  (+X) — tileX = (N-1) − cz,     tileY = (N-1) − cy</li>
 *   <li>WEST  (-X) — tileX = cz,             tileY = (N-1) − cy</li>
 * </ul>
 * If an axis flip turns out wrong on a given face during step 11 tuning, fix
 * it in {@link #sourceTile} alone — every other module references chunks by
 * {@link ChunkCoord} not by source UV.
 *
 * <p>Interior chunks (no outward face) are skipped entirely in v1: they're
 * invisible while the block is whole and the directional-shrink effect
 * never exposes them. Visible chunk count = {@code 6n² − 12n + 8} (= 56 for
 * n=4, 8 for n=2).
 */
public final class TextureSplitter {

    public List<ChunkSpec> split(BlockModel model, int gridN) {
        if (gridN < 1) throw new IllegalArgumentException("gridN must be ≥ 1");
        // Vanilla textures are 16x16. Pre-slice each face into a gridN x gridN tile array.
        // Each face's source is optionally pre-rotated by SourceRotations.of(faceDir),
        // then optionally mirrored by SourceFlips.of(faceDir). Both are user-tunable
        // "rotate/mirror the whole block face" knobs, distinct from per-tile in-plane
        // rotation (TileRotations). Order: rotate THEN flip (matches what the debug
        // commands' messages claim).
        EnumMap<FaceDir, BufferedImage[][]> tilesByFace = new EnumMap<>(FaceDir.class);
        for (FaceDir d : FaceDir.values()) {
            BufferedImage rotated = rotate90Multiples(model.face(d), SourceRotations.of(d));
            BufferedImage flipped = applyFlip(rotated, SourceFlips.of(d));
            tilesByFace.put(d, sliceFace(flipped, gridN));
        }

        List<ChunkSpec> out = new ArrayList<>();
        for (int x = 0; x < gridN; x++) {
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    EnumMap<FaceDir, BufferedImage> outward = new EnumMap<>(FaceDir.class);
                    for (FaceDir d : FaceDir.values()) {
                        if (!d.isOutwardAt(x, y, z, gridN)) continue;
                        int[] src = sourceTile(d, x, y, z, gridN);
                        outward.put(d, tilesByFace.get(d)[src[0]][src[1]]);
                    }
                    if (!outward.isEmpty()) {
                        out.add(new ChunkSpec(new ChunkCoord(x, y, z), outward));
                    }
                }
            }
        }
        return out;
    }

    /** Returns a {@code [tileX][tileY]} array of {@code (16/n)×(16/n)} sub-images. */
    private static BufferedImage[][] sliceFace(BufferedImage face, int n) {
        int size = face.getWidth();
        if (size != face.getHeight()) {
            throw new IllegalArgumentException("Face texture must be square, got " + size + "x" + face.getHeight());
        }
        if (size % n != 0) {
            throw new IllegalArgumentException("Face size " + size + " not divisible by gridN " + n);
        }
        int tileSize = size / n;
        BufferedImage[][] tiles = new BufferedImage[n][n];
        for (int tx = 0; tx < n; tx++) {
            for (int ty = 0; ty < n; ty++) {
                tiles[tx][ty] = face.getSubimage(tx * tileSize, ty * tileSize, tileSize, tileSize);
            }
        }
        return tiles;
    }

    /** Apply horizontal/vertical/both mirror per {@link SourceFlips}. */
    private static BufferedImage applyFlip(BufferedImage src, SourceFlips.Flip flip) {
        if (flip == SourceFlips.Flip.NONE) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            int sy = (flip == SourceFlips.Flip.V || flip == SourceFlips.Flip.HV) ? (h - 1 - y) : y;
            for (int x = 0; x < w; x++) {
                int sx = (flip == SourceFlips.Flip.H || flip == SourceFlips.Flip.HV) ? (w - 1 - x) : x;
                out.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return out;
    }

    /**
     * 90° multiple rotation of a square source texture. Mirrors the same
     * function in SkinAssembler — kept private here so TextureSplitter has
     * no dep on the skin package.
     */
    private static BufferedImage rotate90Multiples(BufferedImage src, int degrees) {
        int turns = (((degrees % 360) + 360) % 360) / 90;
        if (turns == 0) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        int outW = (turns % 2 == 1) ? h : w;
        int outH = (turns % 2 == 1) ? w : h;
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int nx, ny;
                switch (turns) {
                    case 1 -> { nx = h - 1 - y; ny = x; }
                    case 2 -> { nx = w - 1 - x; ny = h - 1 - y; }
                    case 3 -> { nx = y;         ny = w - 1 - x; }
                    default -> { nx = x; ny = y; }
                }
                out.setRGB(nx, ny, rgb);
            }
        }
        return out;
    }

    private static int[] sourceTile(FaceDir d, int cx, int cy, int cz, int n) {
        int last = n - 1;
        return switch (d) {
            // UP and DOWN both use (cx, cz) — vanilla cube up-face and
            // down-face default UVs share image-X = world +X, image-Y = world +Z.
            // The BOTTOM U pre-mirror in SkinAssembler handles the down-face
            // U-flip quirk separately. Earlier `(cx, last - cz)` for UP
            // V-flipped at the chunk-grid level: invisible on V-symmetric
            // textures (oak_planks, stone) but visibly mirrored on
            // V-asymmetric ones (oak_log_top rings, pumpkin_top stem).
            case UP    -> new int[] { cx,        cz };
            case DOWN  -> new int[] { cx,        cz };
            case NORTH -> new int[] { last - cx, last - cy };
            case SOUTH -> new int[] { cx,        last - cy };
            case EAST  -> new int[] { last - cz, last - cy };
            case WEST  -> new int[] { cz,        last - cy };
        };
    }
}
