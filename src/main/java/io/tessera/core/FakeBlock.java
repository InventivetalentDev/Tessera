package io.tessera.core;

import org.bukkit.Location;
import org.joml.Quaternionf;

import java.util.ArrayList;
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
    private final Quaternionf blockRotation;
    private boolean despawned = false;

    public FakeBlock(Location origin, BlockKey blockKey, int gridN, List<ChunkRef> chunks, Quaternionf blockRotation) {
        this.origin = origin.clone();
        this.blockKey = blockKey;
        this.gridN = gridN;
        this.chunks = new ArrayList<>(chunks);
        this.blockRotation = new Quaternionf(blockRotation);
    }

    public Location origin() { return origin.clone(); }
    public BlockKey blockKey() { return blockKey; }
    public int gridN() { return gridN; }
    /**
     * Returns the live chunk list. Mutable — the progress listener appends to
     * it as the wave front lazily spawns additional chunks. Main thread only.
     */
    public List<ChunkRef> chunks() { return chunks; }
    public boolean despawned() { return despawned; }

    /**
     * The blockstate-variant rotation applied to the cube as a whole (the
     * {@code L} matrix in {@code BlockGeometry}). Identity for blocks that
     * use only the canonical variant. Effects that reason about chunk
     * positions in world space must pre-multiply per-chunk local centers
     * by this to recover the post-rotation arrangement.
     */
    public Quaternionf blockRotation() { return new Quaternionf(blockRotation); }

    /** Removes every spawned ItemDisplay. Idempotent. Must be called on the main thread. */
    public void despawn() {
        if (despawned) return;
        despawned = true;
        for (ChunkRef c : chunks) {
            if (!c.display().isDead()) c.display().remove();
        }
    }
}
