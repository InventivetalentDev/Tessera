package io.tessera.core;

import org.bukkit.Location;

import java.util.List;

/**
 * Runtime handle to a sub-block effect: the original block at {@link #origin}
 * has been visually replaced by {@link #chunks} small player-head display
 * entities arranged in a chunkGridSize³ grid. Effects read this and apply
 * transforms, scheduling, etc.
 */
public final class FakeBlock {

    private final Location origin;
    private final BlockKey blockKey;
    private final int gridN;
    private final List<ChunkRef> chunks;
    private boolean despawned = false;

    public FakeBlock(Location origin, BlockKey blockKey, int gridN, List<ChunkRef> chunks) {
        this.origin = origin.clone();
        this.blockKey = blockKey;
        this.gridN = gridN;
        this.chunks = List.copyOf(chunks);
    }

    public Location origin() { return origin.clone(); }
    public BlockKey blockKey() { return blockKey; }
    public int gridN() { return gridN; }
    public List<ChunkRef> chunks() { return chunks; }
    public boolean despawned() { return despawned; }

    /** Removes every spawned ItemDisplay. Idempotent. Must be called on the main thread. */
    public void despawn() {
        if (despawned) return;
        despawned = true;
        for (ChunkRef c : chunks) {
            if (!c.display().isDead()) c.display().remove();
        }
    }
}
