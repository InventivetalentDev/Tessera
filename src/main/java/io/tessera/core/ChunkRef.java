package io.tessera.core;

import org.bukkit.entity.ItemDisplay;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * Runtime handle to one spawned chunk display entity. Created by
 * {@code FakeBlockFactory#create} and consumed by effects.
 *
 * <p>{@link #localCenter} is the chunk's center in block-local coordinates
 * ([0,1]³); effects use it to compute things like wave-arrival times.
 * {@link #manhattanFromSurface} is 0 for face-center chunks, 1 for edges,
 * 2 for corners — handy as a sort key for some effects.
 */
public final class ChunkRef {

    private final ItemDisplay display;
    private final ChunkCoord coord;
    private final Vector3f localCenter;
    private final EnumSet<FaceDir> outwardFaces;

    public ChunkRef(ItemDisplay display, ChunkCoord coord, Vector3f localCenter, EnumSet<FaceDir> outwardFaces) {
        this.display = display;
        this.coord = coord;
        this.localCenter = new Vector3f(localCenter);
        this.outwardFaces = EnumSet.copyOf(outwardFaces);
    }

    public ItemDisplay display() { return display; }
    public ChunkCoord coord() { return coord; }
    public Vector3f localCenter() { return new Vector3f(localCenter); }
    public EnumSet<FaceDir> outwardFaces() { return EnumSet.copyOf(outwardFaces); }

    public int manhattanFromSurface() {
        // 1 outward face → 0, 2 → 1, 3 → 2.
        return outwardFaces.size() - 1;
    }
}
