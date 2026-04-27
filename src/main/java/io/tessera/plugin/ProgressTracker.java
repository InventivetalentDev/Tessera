package io.tessera.plugin;

import io.tessera.core.BlockKey;
import io.tessera.core.FakeBlock;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for in-flight progress-driven break animations. Keyed by
 * block position; a secondary index maps each player to the block they're
 * currently mining so we can tear down stale trackers when they switch
 * targets.
 *
 * <p>All methods are intended to be called on the main thread (Bukkit
 * events are main-thread); the underlying maps are concurrent only for
 * defensive cleanup paths (player quit, watchdog).
 */
public final class ProgressTracker {

    /** Composite key for a block position in a specific world. */
    public record BlockPosKey(UUID worldUid, int x, int y, int z) {
        public static BlockPosKey of(Location loc) {
            return new BlockPosKey(loc.getWorld().getUID(),
                    loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        }
    }

    public enum State { FORWARD, REVERSING }

    /**
     * Mutable state for one block being progressively broken. Created on
     * the first non-zero progress event for a block; disposed on real
     * break, full reverse, watchdog timeout, or player quit/world change.
     */
    public static final class TrackedBreak {
        public UUID currentPlayerId;
        public final BlockKey key;
        public final Location origin;          // block-cell lower-NW-down corner
        public final BlockData originalBlockData;
        public Vector eyeDir;                  // refreshed when ownership transfers
        public final FakeBlock fakeBlock;
        public double[] chunkT;                // wave position per chunk; recomputed on owner transfer
        public final float[] baseScales;       // per-chunk spawn scales (immutable across ownership transfers)
        public final float[] currentScales;    // last-applied scale per chunk; updated by applyAtProgress
        public double lastAppliedProgress;
        public long lastUpdateTickMs;
        public State state;
        public boolean barrierSent;
        public BukkitTask reverseTask;         // non-null only while REVERSING

        public TrackedBreak(UUID playerId, BlockKey key, Location origin,
                            BlockData originalBlockData, Vector eyeDir,
                            FakeBlock fakeBlock, double[] chunkT,
                            float[] baseScales, float[] currentScales) {
            this.currentPlayerId = playerId;
            this.key = key;
            this.origin = origin;
            this.originalBlockData = originalBlockData;
            this.eyeDir = eyeDir;
            this.fakeBlock = fakeBlock;
            this.chunkT = chunkT;
            this.baseScales = baseScales;
            this.currentScales = currentScales;
            this.lastAppliedProgress = 0d;
            this.lastUpdateTickMs = System.currentTimeMillis();
            this.state = State.FORWARD;
            this.barrierSent = false;
            this.reverseTask = null;
        }
    }

    private final Map<BlockPosKey, TrackedBreak> byBlock = new ConcurrentHashMap<>();
    private final Map<UUID, BlockPosKey> byPlayer = new ConcurrentHashMap<>();

    public TrackedBreak get(BlockPosKey key) {
        return byBlock.get(key);
    }

    public BlockPosKey activeBlockFor(UUID playerId) {
        return byPlayer.get(playerId);
    }

    /** Register a freshly-created tracker. Replaces any existing entry for the player. */
    public void put(BlockPosKey key, TrackedBreak tb) {
        byBlock.put(key, tb);
        byPlayer.put(tb.currentPlayerId, key);
    }

    /**
     * Reassign ownership to a new player (used for "latest progress wins").
     * Removes the old player's secondary-index entry and installs the new one.
     */
    public void transferOwner(BlockPosKey key, UUID oldPlayerId, UUID newPlayerId) {
        if (oldPlayerId != null) {
            byPlayer.remove(oldPlayerId, key);
        }
        byPlayer.put(newPlayerId, key);
    }

    /** Remove the tracker for {@code key}. Returns the removed entry, or null. */
    public TrackedBreak remove(BlockPosKey key) {
        TrackedBreak tb = byBlock.remove(key);
        if (tb != null) {
            byPlayer.remove(tb.currentPlayerId, key);
        }
        return tb;
    }

    /** Snapshot view of all active trackers. Used for shutdown / iteration. */
    public Iterable<Map.Entry<BlockPosKey, TrackedBreak>> snapshot() {
        return byBlock.entrySet();
    }

    public int size() {
        return byBlock.size();
    }
}
