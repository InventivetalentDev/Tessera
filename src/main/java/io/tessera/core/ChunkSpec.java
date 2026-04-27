package io.tessera.core;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;

/**
 * One sub-block chunk to be rendered as a player-head ItemDisplay. Holds the
 * chunk's grid position and the texture tiles that should appear on each
 * outward-facing side of the tiny cube.
 *
 * <p>Built by {@code split.TextureSplitter} from the parent {@code BlockModel}'s
 * face textures, then consumed by {@code skin.HeadSkinPacker} (groups them
 * onto 64×64 head skins) and {@code assemble.FakeBlockFactory} (spawns the
 * matching ItemDisplay).
 *
 * <p>{@link #outwardTiles} is an {@link EnumMap} keyed by {@link FaceDir}; only
 * outward faces (the ones derived from {@link FaceDir#isOutwardAt}) are
 * populated. Interior chunks (no outward face) are dropped before reaching
 * here in v1.
 */
public final class ChunkSpec {

    private final ChunkCoord coord;
    private final EnumMap<FaceDir, BufferedImage> outwardTiles;
    private final EnumSet<FaceDir> outwardFaces;

    public ChunkSpec(ChunkCoord coord, Map<FaceDir, BufferedImage> outwardTiles) {
        this.coord = Objects.requireNonNull(coord, "coord");
        this.outwardTiles = new EnumMap<>(FaceDir.class);
        this.outwardTiles.putAll(outwardTiles);
        if (this.outwardTiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "ChunkSpec must have at least one outward tile (interior chunks are filtered out earlier)");
        }
        this.outwardFaces = EnumSet.copyOf(this.outwardTiles.keySet());
    }

    public ChunkCoord coord() {
        return coord;
    }

    public EnumSet<FaceDir> outwardFaces() {
        return EnumSet.copyOf(outwardFaces);
    }

    public BufferedImage tile(FaceDir face) {
        return outwardTiles.get(face);
    }

    public Map<FaceDir, BufferedImage> outwardTiles() {
        return Map.copyOf(outwardTiles);
    }

    /** Number of outward faces: 1 for face-center, 2 for edge, 3 for corner chunks. */
    public int outwardCount() {
        return outwardFaces.size();
    }
}
