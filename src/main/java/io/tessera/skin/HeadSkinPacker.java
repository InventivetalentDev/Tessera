package io.tessera.skin;

import io.tessera.core.ChunkSpec;
import io.tessera.core.FaceDir;
import io.tessera.core.HeadFace;
import io.tessera.util.Hashing;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds one {@link HeadSkin} per {@link ChunkSpec} and dedupes by content
 * hash so identical chunks share a single MineSkin upload. Adapted from
 * Mosaikin's SkinPacker — same dedup mechanic, different per-chunk packing
 * (Mosaikin packed 6 frames; we pack 6 cube faces of one chunk).
 *
 * <p>Each chunk's tile map is built by mapping each {@link HeadFace} back to
 * its {@link FaceDir} and pulling the chunk's tile for that direction.
 * Non-outward faces (FaceDirs not in {@code chunk.outwardFaces()}) get a
 * filler tile — a copy of the primary outward tile — so the head's hash is
 * deterministic and identical chunks (e.g. all face-center chunks of a
 * uniform stone block) collapse to one upload.
 *
 * <p>For a uniform 16×16 stone block at gridN=4, the dedup count is:
 * <ul>
 *   <li>1 head shared by all 24 face-center chunks (1 outward + 5 filler, all identical)</li>
 *   <li>1 head shared by all 24 edge chunks (2 outward + 4 filler, all identical)</li>
 *   <li>1 head shared by all 8 corner chunks (3 outward + 3 filler, all identical)</li>
 * </ul>
 * Total: 3 unique skin uploads for stone, regardless of total chunk count.
 */
public final class HeadSkinPacker {

    public record Result(List<HeadSkin> uniqueHeads, Map<ChunkSpec, HeadSkin> chunkToHead) {}

    public Result pack(List<ChunkSpec> chunks) {
        List<ChunkSpec> ordered = new ArrayList<>(chunks);
        ordered.sort(Comparator.comparing(ChunkSpec::coord));

        Map<String, HeadSkin> dedup = new HashMap<>();
        Map<ChunkSpec, HeadSkin> mapping = new LinkedHashMap<>();
        List<HeadSkin> uniqueHeads = new ArrayList<>();

        for (ChunkSpec chunk : ordered) {
            EnumMap<HeadFace, BufferedImage> faceTiles = buildFaceTiles(chunk);
            String hash = hashFaceTiles(faceTiles);

            HeadSkin head = dedup.computeIfAbsent(hash, h -> {
                HeadSkin newHead = new HeadSkin(UUID.randomUUID(), h, faceTiles);
                uniqueHeads.add(newHead);
                return newHead;
            });
            head.addChunk(chunk);
            mapping.put(chunk, head);
        }

        return new Result(uniqueHeads, mapping);
    }

    private static EnumMap<HeadFace, BufferedImage> buildFaceTiles(ChunkSpec chunk) {
        EnumMap<HeadFace, BufferedImage> out = new EnumMap<>(HeadFace.class);
        BufferedImage filler = chunk.tile(primaryOutward(chunk));

        for (HeadFace hf : HeadFace.values()) {
            FaceDir matching = headFaceToFaceDir(hf);
            BufferedImage tile = chunk.tile(matching);
            out.put(hf, applyShade(tile != null ? tile : filler, matching.shade()));
        }
        return out;
    }

    private static BufferedImage applyShade(BufferedImage src, float factor) {
        if (factor == 1.0f) return src;
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a =  (argb >> 24) & 0xFF;
                int r = (int)(((argb >> 16) & 0xFF) * factor);
                int g = (int)(((argb >>  8) & 0xFF) * factor);
                int b = (int)(( argb        & 0xFF) * factor);
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static String hashFaceTiles(Map<HeadFace, BufferedImage> tiles) {
        List<String> parts = new ArrayList<>(6);
        for (HeadFace hf : HeadFace.PACK_ORDER) {
            BufferedImage tile = tiles.get(hf);
            parts.add(tile == null ? "empty" : Hashing.sha256OfImage(tile));
        }
        return Hashing.sha256OfStrings(parts);
    }

    private static FaceDir primaryOutward(ChunkSpec chunk) {
        for (FaceDir d : FaceDir.values()) {
            if (chunk.outwardFaces().contains(d)) return d;
        }
        throw new IllegalStateException("ChunkSpec has no outward faces: " + chunk.coord());
    }

    /**
     * Inverse of {@link #faceDirToHeadFace}. Lives here so the canonical
     * mapping between block-local FaceDir and skin-local HeadFace stays
     * in one place.
     *
     * <p><b>RIGHT↔WEST, LEFT↔EAST is intentional</b> and matches the vanilla
     * player-head model's cube UVs. The model's {@code east} (+X) face
     * samples skin pixels {@code (16, 8)-(24, 16)} — the LEFT slot
     * (wearer's left cheek; with Steve facing +Z south, the wearer's left
     * side is at +X). Symmetrically, the model's {@code west} (-X) face
     * samples the RIGHT slot. Under canonical rotation, world east shows
     * LEFT-slot pixels and world west shows RIGHT-slot pixels, so the
     * outward EAST tile must be packed into LEFT and the outward WEST tile
     * into RIGHT. Mirroring this naively (LEFT→WEST, RIGHT→EAST) is
     * invisible on uniform-textured blocks but produces wrong textures on
     * the top/bottom rows of east/west faces for non-uniform blocks like
     * oak_log: those chunks fill RIGHT with the EAST source bark, which
     * the renderer then shows on world west, while LEFT receives the
     * cross-section filler that the renderer shows on world east.
     */
    public static FaceDir headFaceToFaceDir(HeadFace f) {
        return switch (f) {
            case TOP    -> FaceDir.UP;
            case BOTTOM -> FaceDir.DOWN;
            case FRONT  -> FaceDir.SOUTH;
            case BACK   -> FaceDir.NORTH;
            case RIGHT  -> FaceDir.WEST;
            case LEFT   -> FaceDir.EAST;
        };
    }

    public static HeadFace faceDirToHeadFace(FaceDir d) {
        return switch (d) {
            case UP    -> HeadFace.TOP;
            case DOWN  -> HeadFace.BOTTOM;
            case SOUTH -> HeadFace.FRONT;
            case NORTH -> HeadFace.BACK;
            case EAST  -> HeadFace.LEFT;
            case WEST  -> HeadFace.RIGHT;
        };
    }
}
