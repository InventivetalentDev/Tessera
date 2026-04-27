package io.tessera.plugin;

import io.papermc.paper.event.block.BlockBreakProgressUpdateEvent;
import io.tessera.assemble.FakeBlockFactory;
import io.tessera.core.BlockKey;
import io.tessera.core.FakeBlock;
import io.tessera.core.VariantKey;
import io.tessera.effect.builtin.DirectionalShrinkEffect;
import io.tessera.plugin.ProgressTracker.BlockPosKey;
import io.tessera.plugin.ProgressTracker.State;
import io.tessera.plugin.ProgressTracker.TrackedBreak;
import io.tessera.plugin.TesseraConfig.AnimationMode;
import io.tessera.skin.HeadsRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives the chunked-shrink animation off live mining progress
 * ({@link BlockBreakProgressUpdateEvent}) so chunks shrink in lockstep with
 * the player's mining and the FakeBlock is gone exactly as the real block
 * breaks. Registers reverse-on-cancel and player-quit cleanup.
 *
 * <p>This listener does nothing when {@link AnimationMode#POST_BREAK} is
 * configured; in that mode the legacy post-break path in
 * {@link BlockBreakListener} runs instead.
 */
public final class BlockBreakProgressListener implements Listener {

    private static final long WATCHDOG_PERIOD_TICKS = 5L; // 250ms
    private static final long WATCHDOG_STALE_MS = 500L;
    private static final int FORWARD_INTERP_TICKS = 1;
    private static final int REVERSE_INTERP_TICKS = 1;

    private final TesseraPlugin plugin;
    private final FakeBlockFactory factory;
    private final HeadsRegistry registry;
    private final AtomicInteger active;
    private final ProgressTracker tracker;
    private BukkitTask watchdog;

    public BlockBreakProgressListener(TesseraPlugin plugin, FakeBlockFactory factory,
                                      HeadsRegistry registry, AtomicInteger active,
                                      ProgressTracker tracker) {
        this.plugin = plugin;
        this.factory = factory;
        this.registry = registry;
        this.active = active;
        this.tracker = tracker;
    }

    /** Start the watchdog timer. Called from plugin onEnable after registration. */
    public void start() {
        this.watchdog = Bukkit.getScheduler().runTaskTimer(
                plugin, this::watchdogTick, WATCHDOG_PERIOD_TICKS, WATCHDOG_PERIOD_TICKS);
    }

    /** Stop watchdog and tear down all in-flight trackers. Called on plugin disable. */
    public void shutdown() {
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
        List<BlockPosKey> keys = new ArrayList<>();
        tracker.snapshot().forEach(e -> keys.add(e.getKey()));
        for (BlockPosKey k : keys) {
            TrackedBreak tb = tracker.remove(k);
            if (tb == null) continue;
            disposeImmediate(tb, /*restoreBlock=*/ true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProgress(BlockBreakProgressUpdateEvent event) {
        TesseraConfig cfg = plugin.tesseraConfig();
        if (cfg.animationMode() != AnimationMode.PROGRESS) return;

        Entity src = event.getEntity();
        if (!(src instanceof Player player)) return;

        Block block = event.getBlock();
        Location breakLoc = block.getLocation();
        BlockPosKey posKey = BlockPosKey.of(breakLoc);
        double progress = event.getProgress();

        TrackedBreak tb = tracker.get(posKey);

        if (tb == null) {
            if (progress <= 0d) return;
            spawnAndRegister(player, block, breakLoc, posKey, progress, cfg);
            return;
        }

        // Tracker exists.
        if (!tb.currentPlayerId.equals(player.getUniqueId())) {
            // Latest progress wins: only steal ownership if the new player
            // is genuinely further along than the current owner.
            if (progress <= tb.lastAppliedProgress) return;
            transferOwnership(tb, posKey, player, progress, cfg);
            return;
        }

        // Same player.
        if (progress <= 0d) {
            // Treat as cancel: drop into reverse if not already reversing.
            beginReverse(tb, posKey, cfg);
            return;
        }

        if (tb.state == State.REVERSING) {
            // Player resumed; cancel the in-flight reverse and continue forward.
            cancelReverseTask(tb);
            tb.state = State.FORWARD;
        }

        if (Math.abs(progress - tb.lastAppliedProgress) < cfg.progressMinDelta()) {
            // Server is still emitting events (player is actively mining), even
            // though the value didn't change enough to repaint. Refresh the
            // watchdog timestamp so it doesn't trip while mining is still live.
            tb.lastUpdateTickMs = System.currentTimeMillis();
            return;
        }

        applyForward(tb, progress, cfg);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        teardownPlayer(event.getPlayer().getUniqueId(), /*restoreBlock=*/ false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        teardownPlayer(event.getPlayer().getUniqueId(), /*restoreBlock=*/ false);
    }

    private void spawnAndRegister(Player player, Block block, Location breakLoc,
                                  BlockPosKey posKey, double progress, TesseraConfig cfg) {
        Material mat = block.getType();
        BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());
        if (!cfg.enables(key.asString())) return;
        if (!registry.has(key)) return; // Skip; post-break path will handle (possibly via runtime bake).
        if (active.get() >= cfg.maxConcurrentFakeBlocks()) {
            if (cfg.debug()) plugin.getLogger().info(
                    "[debug-progress] skip " + key + " at " + posKey + " (concurrency cap)");
            return;
        }

        // Tear down any pre-existing tracker owned by this player on a different block.
        BlockPosKey prev = tracker.activeBlockFor(player.getUniqueId());
        if (prev != null && !prev.equals(posKey)) {
            TrackedBreak old = tracker.remove(prev);
            if (old != null) disposeImmediate(old, /*restoreBlock=*/ true);
        }

        // Resolve the blockstate variant rotation so non-default variants
        // (axis=x logs, facing=east furnaces, ...) render and animate
        // aligned to the visible cube. Identity rotation = canonical
        // variant, the safe fallback for unsupported states.
        BlockData blockData = block.getBlockData();
        String fullStateKey = VariantKey.fromBlockData(blockData);
        String matchedKey = VariantKey.pickMatching(fullStateKey, registry.variantsFor(key).keySet());
        Quaternionf blockRotation = registry.rotationFor(key, matchedKey);

        active.incrementAndGet();
        FakeBlock fb;
        try {
            fb = factory.create(breakLoc, key, blockRotation);
        } catch (RuntimeException re) {
            active.decrementAndGet();
            plugin.getLogger().warning("Failed to spawn FakeBlock for " + key + ": " + re.getMessage());
            return;
        }

        Vector eyeDir = player.getEyeLocation().getDirection();
        double[] chunkT = io.tessera.effect.ChunkWaveSampler.precomputeT(fb, eyeDir);
        float[] base = DirectionalShrinkEffect.captureBaseScales(fb);
        float[] current = base.clone();

        TrackedBreak tb = new TrackedBreak(player.getUniqueId(), key, fb.origin(),
                blockData, eyeDir, fb, chunkT, base, current);
        tracker.put(posKey, tb);

        if (cfg.clientHideRealBlock()) {
            try {
                player.sendBlockChange(breakLoc, Material.BARRIER.createBlockData());
                tb.barrierSent = true;
            } catch (RuntimeException re) {
                plugin.getLogger().warning("sendBlockChange(BARRIER) failed: " + re.getMessage());
            }
        }

        if (cfg.debug()) plugin.getLogger().info(
                "[debug-progress] spawn " + key + " at " + posKey
                        + " player=" + player.getName() + " progress=" + fmt(progress)
                        + " barrier=" + tb.barrierSent);

        applyForward(tb, progress, cfg);
    }

    private void applyForward(TrackedBreak tb, double progress, TesseraConfig cfg) {
        DirectionalShrinkEffect.applyAtProgress(tb.fakeBlock, tb.chunkT, tb.baseScales, tb.currentScales,
                progress, cfg.waveWindow(), FORWARD_INTERP_TICKS, cfg.progressMinDelta());
        tb.lastAppliedProgress = progress;
        tb.lastUpdateTickMs = System.currentTimeMillis();
    }

    private void transferOwnership(TrackedBreak tb, BlockPosKey posKey, Player newPlayer,
                                   double progress, TesseraConfig cfg) {
        UUID oldId = tb.currentPlayerId;
        Player oldPlayer = Bukkit.getPlayer(oldId);
        if (oldPlayer != null && tb.barrierSent) {
            try {
                oldPlayer.sendBlockChange(tb.origin, tb.originalBlockData);
            } catch (RuntimeException ignored) { /* old player may have left chunk */ }
        }

        tb.currentPlayerId = newPlayer.getUniqueId();
        tb.eyeDir = newPlayer.getEyeLocation().getDirection();
        tb.chunkT = io.tessera.effect.ChunkWaveSampler.precomputeT(tb.fakeBlock, tb.eyeDir);
        tracker.transferOwner(posKey, oldId, newPlayer.getUniqueId());

        if (cfg.clientHideRealBlock()) {
            try {
                newPlayer.sendBlockChange(tb.origin, Material.BARRIER.createBlockData());
                tb.barrierSent = true;
            } catch (RuntimeException re) {
                plugin.getLogger().warning("sendBlockChange(BARRIER) on transfer failed: " + re.getMessage());
            }
        }

        if (cfg.debug()) plugin.getLogger().info(
                "[debug-progress] transfer " + tb.key + " at " + posKey
                        + " from=" + oldId + " to=" + newPlayer.getName()
                        + " progress=" + fmt(progress));

        if (tb.state == State.REVERSING) {
            cancelReverseTask(tb);
            tb.state = State.FORWARD;
        }
        applyForward(tb, progress, cfg);
    }

    private void beginReverse(TrackedBreak tb, BlockPosKey posKey, TesseraConfig cfg) {
        if (tb.state == State.REVERSING) return;
        tb.state = State.REVERSING;
        final double startProgress = tb.lastAppliedProgress;
        final long startMs = System.currentTimeMillis();
        final int durationMs = (int) Math.max(50L, Math.min(300L, Math.round(startProgress * 300d)));

        if (cfg.debug()) plugin.getLogger().info(
                "[debug-progress] reverse-start " + tb.key + " at " + posKey
                        + " from=" + fmt(startProgress) + " durationMs=" + durationMs);

        tb.reverseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // Re-fetch to make sure the tracker wasn't disposed underneath us.
            TrackedBreak live = tracker.get(posKey);
            if (live != tb || live.state != State.REVERSING) {
                cancelReverseTask(tb);
                return;
            }
            long elapsed = System.currentTimeMillis() - startMs;
            double rp;
            if (elapsed >= durationMs) {
                rp = 0d;
            } else {
                rp = startProgress * (1.0 - (double) elapsed / (double) durationMs);
            }
            DirectionalShrinkEffect.applyAtProgress(tb.fakeBlock, tb.chunkT, tb.baseScales, tb.currentScales,
                    rp, cfg.waveWindow(), REVERSE_INTERP_TICKS, cfg.progressMinDelta());
            tb.lastAppliedProgress = rp;
            tb.lastUpdateTickMs = System.currentTimeMillis();
            if (rp <= 0d) {
                tracker.remove(posKey);
                cancelReverseTask(tb);
                disposeImmediate(tb, /*restoreBlock=*/ true);
                if (cfg.debug()) plugin.getLogger().info(
                        "[debug-progress] reverse-done " + tb.key + " at " + posKey);
            }
        }, 1L, 1L);
    }

    /**
     * Called by {@link BlockBreakListener#onBreak} when the real block breaks
     * and a tracker exists for that position. Despawn FakeBlock immediately
     * (vanilla has already removed the real block; no sendBlockChange needed).
     */
    void onRealBreak(BlockPosKey posKey) {
        TrackedBreak tb = tracker.remove(posKey);
        if (tb == null) return;
        cancelReverseTask(tb);
        disposeImmediate(tb, /*restoreBlock=*/ false);
        if (plugin.tesseraConfig().debug()) plugin.getLogger().info(
                "[debug-progress] real-break " + tb.key + " at " + posKey);
    }

    /** Public so BlockBreakListener can ask whether a position is tracked. */
    public boolean isTracked(BlockPosKey posKey) {
        return tracker.get(posKey) != null;
    }

    private void teardownPlayer(UUID playerId, boolean restoreBlock) {
        BlockPosKey posKey = tracker.activeBlockFor(playerId);
        if (posKey == null) return;
        TrackedBreak tb = tracker.remove(posKey);
        if (tb == null) return;
        cancelReverseTask(tb);
        disposeImmediate(tb, restoreBlock);
        if (plugin.tesseraConfig().debug()) plugin.getLogger().info(
                "[debug-progress] teardown player=" + playerId + " posKey=" + posKey);
    }

    private void watchdogTick() {
        long now = System.currentTimeMillis();
        TesseraConfig cfg = plugin.tesseraConfig();
        // Snapshot to avoid mutation during iteration.
        List<BlockPosKey> stale = new ArrayList<>();
        for (var e : tracker.snapshot()) {
            TrackedBreak tb = e.getValue();
            if (tb.state == State.FORWARD && (now - tb.lastUpdateTickMs) > WATCHDOG_STALE_MS) {
                stale.add(e.getKey());
            }
        }
        for (BlockPosKey k : stale) {
            TrackedBreak tb = tracker.get(k);
            if (tb == null) continue;
            if (cfg.debug()) plugin.getLogger().info(
                    "[debug-progress] watchdog-cancel " + tb.key + " at " + k
                            + " stale=" + (now - tb.lastUpdateTickMs) + "ms");
            beginReverse(tb, k, cfg);
        }
    }

    private void disposeImmediate(TrackedBreak tb, boolean restoreBlock) {
        if (restoreBlock && tb.barrierSent) {
            Player p = Bukkit.getPlayer(tb.currentPlayerId);
            if (p != null) {
                try {
                    p.sendBlockChange(tb.origin, tb.originalBlockData);
                } catch (RuntimeException ignored) { /* player may have unloaded chunk */ }
            }
        }
        try {
            tb.fakeBlock.despawn();
        } catch (RuntimeException re) {
            plugin.getLogger().warning("despawn FakeBlock failed: " + re.getMessage());
        }
        active.decrementAndGet();
    }

    private static void cancelReverseTask(TrackedBreak tb) {
        if (tb.reverseTask != null) {
            try { tb.reverseTask.cancel(); } catch (RuntimeException ignored) {}
            tb.reverseTask = null;
        }
    }

    private static String fmt(double d) { return String.format("%.3f", d); }
}
