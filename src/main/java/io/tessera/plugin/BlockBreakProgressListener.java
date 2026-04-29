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
import io.tessera.plugin.TesseraConfig.CollapseStyle;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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
    // Stale threshold is auto-tuned per-tracker from the observed inter-event
    // gap (Paper fires BlockBreakProgressUpdateEvent on destroy-stage
    // boundaries — every ~50ms for instamine, ~600ms for obsidian, ~4s for
    // iron-pick-on-obsidian, etc.). We clamp into [MIN, MAX] so cancel
    // detection stays responsive and we don't wait forever on stuck trackers.
    // Until the second progress event arrives we use BOOTSTRAP, since we
    // have no measurement yet and the first stage of a slow block can be
    // longer than MIN.
    private static final long WATCHDOG_STALE_BOOTSTRAP_MS = 2500L;
    private static final long WATCHDOG_STALE_MIN_MS = 800L;
    private static final long WATCHDOG_STALE_MAX_MS = 4000L;
    private static final int FORWARD_INTERP_TICKS_FALLBACK = 1;
    private static final int REVERSE_INTERP_TICKS = 1;
    private static final long MS_PER_TICK = 50L;
    // Vanilla mining surfaces 10 destroy stages, so each progress event nominally
    // covers 0.1 of progress. We extrapolate one stage ahead so the client can
    // smoothly lerp through the gap until the next event arrives.
    private static final double STAGE_PER_EVENT = 0.1d;
    // Cap predictive lookahead so a long pause near p≈0.9 doesn't force the
    // client to lerp toward p≥1.0 (which would visually finish the break).
    private static final double PREDICTIVE_TARGET_MAX = 0.95d;
    // Don't extrapolate beyond this gap — slow blocks (obsidian etc.) can have
    // multi-second gaps and we don't want a single event to stretch a smooth
    // interpolation that long if mining stops.
    private static final long MAX_INTERP_MS = 800L;

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
            // Vanilla emits a progress=0 event both when the player STOPS
            // mining (genuine cancel) and when they START mining (the
            // stage=-1 transition that arrives right after PlayerInteract-
            // Event LEFT_CLICK_BLOCK). After a speculative pre-spawn we'd
            // otherwise treat the start as a cancel and reverse immediately —
            // visible as a short shrink-then-respawn jump at the beginning
            // of every break. Only reverse if we've already observed
            // positive progress, i.e. there's something to roll back.
            if (tb.lastAppliedProgress <= 0d) {
                tb.lastUpdateTickMs = System.currentTimeMillis();
                return;
            }
            beginReverse(tb, posKey, cfg);
            return;
        }

        if (tb.state == State.REVERSING) {
            // Player resumed; cancel the in-flight reverse and continue forward.
            cancelReverseTask(tb);
            tb.state = State.FORWARD;
        }

        // First real progress event for a speculative spawn confirms the
        // player is actually mining. Drop the flag so the watchdog stops
        // using the (short) left-click grace window for staleness.
        if (tb.speculative) {
            tb.speculative = false;
            if (cfg.debug()) plugin.getLogger().info(
                    "[debug-progress] speculative-confirmed " + tb.key + " at " + posKey
                            + " progress=" + fmt(progress));
        }

        // Update the per-tracker inter-event EMA so the watchdog can scale
        // its stale threshold to the block's actual cadence (slow blocks
        // emit events ~600-1000ms apart and would otherwise oscillate).
        long now = System.currentTimeMillis();
        long gap = now - tb.lastUpdateTickMs;
        tb.avgEventGapMs = (tb.avgEventGapMs == 0L) ? gap : (tb.avgEventGapMs * 3L + gap) / 4L;

        if (Math.abs(progress - tb.lastAppliedProgress) < cfg.progressMinDelta()) {
            // Server is still emitting events (player is actively mining), even
            // though the value didn't change enough to repaint. Refresh the
            // watchdog timestamp so it doesn't trip while mining is still live.
            tb.lastUpdateTickMs = now;
            return;
        }

        applyForward(tb, progress, cfg);
    }

    /**
     * Speculative pre-spawn on left-click. Vanilla's first
     * BlockBreakProgressUpdateEvent arrives only when the client crosses the
     * 0.1 destroy-stage boundary, which on a default-speed mine is ~600ms
     * after the player started swinging. Catching the click lets us spawn
     * immediately so the animation actually starts when the player starts
     * mining, not 600ms in.
     *
     * <p>If the click was a no-commit swing (player didn't actually start
     * mining), no progress event will arrive and either the configured grace
     * window or the watchdog reverses the speculative spawn back out.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        TesseraConfig cfg = plugin.tesseraConfig();
        if (cfg.animationMode() != AnimationMode.PROGRESS) return;
        if (!cfg.startOnLeftClick()) return;

        Block block = event.getClickedBlock();
        if (block == null) return;
        Player player = event.getPlayer();

        // Skip instamine: post-break path will handle it cleanly with the
        // full timed wave; pre-spawning would just be redundant.
        try {
            if (block.getBreakSpeed(player) >= 1.0f) return;
        } catch (NoSuchMethodError ignored) {
            // Older Paper without Block#getBreakSpeed; fall through and pre-spawn anyway.
        }

        Location breakLoc = block.getLocation();
        BlockPosKey posKey = BlockPosKey.of(breakLoc);
        // Already tracking this block - the in-flight tracker handles further events.
        if (tracker.get(posKey) != null) return;

        spawnAndRegister(player, block, breakLoc, posKey, 0d, cfg);
        TrackedBreak tb = tracker.get(posKey);
        if (tb == null) return; // gated out (not enabled / not baked / cap)
        tb.speculative = true;
        tb.initialGapEstimateMs = estimateInitialGapMs(block, player, cfg);
        // Drive the watchdog off the configured grace, not the bootstrap default,
        // so an idle swing rolls back quickly.
        tb.lastUpdateTickMs = System.currentTimeMillis();
        if (cfg.debug()) plugin.getLogger().info(
                "[debug-progress] speculative-spawn at " + posKey
                        + " player=" + player.getName()
                        + " initialGapMs=" + tb.initialGapEstimateMs);
    }

    /**
     * Auto-tuned stale threshold: speculative trackers use the configured
     * left-click grace window so a no-commit swing rolls back quickly;
     * otherwise BOOTSTRAP until we've seen at least one inter-event gap,
     * then 3x EMA + 200ms slack clamped to [MIN, MAX].
     */
    private static long staleThresholdFor(TrackedBreak tb, TesseraConfig cfg) {
        if (tb.speculative) return Math.max(100L, cfg.leftClickGraceMs());
        if (tb.avgEventGapMs == 0L) return WATCHDOG_STALE_BOOTSTRAP_MS;
        long base = tb.avgEventGapMs * 3L + 200L;
        if (base < WATCHDOG_STALE_MIN_MS) return WATCHDOG_STALE_MIN_MS;
        if (base > WATCHDOG_STALE_MAX_MS) return WATCHDOG_STALE_MAX_MS;
        return base;
    }

    /**
     * Best-effort estimate of the gap between vanilla's 0.1-progress events
     * for this (block, player, tool) combo. Uses Paper's
     * {@code Block.getBreakSpeed(player)} (per-tick mining progress) when
     * available; falls back to a reasonable default if the API throws.
     * Returned value is clamped to [MS_PER_TICK, MAX_INTERP_MS].
     */
    private static long estimateInitialGapMs(Block block, Player player, TesseraConfig cfg) {
        try {
            float perTick = block.getBreakSpeed(player);
            if (perTick > 0f) {
                long perStageMs = (long) Math.ceil(STAGE_PER_EVENT / perTick * MS_PER_TICK);
                if (perStageMs < MS_PER_TICK) return MS_PER_TICK;
                if (perStageMs > MAX_INTERP_MS) return MAX_INTERP_MS;
                return perStageMs;
            }
        } catch (NoSuchMethodError | RuntimeException ignored) {
            // Older Paper or unexpected state - use the configured grace window
            // as a conservative default.
        }
        return Math.max(MS_PER_TICK, Math.min(MAX_INTERP_MS, cfg.leftClickGraceMs()));
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
        // Read biome tint synchronously here (we're on main); the resulting
        // BakeKey identifies the registry entry for this (block, biome).
        int tint = cfg.enableTintedBlocks() ? io.tessera.nms.BlockTintReader.read(block) : 0;
        io.tessera.core.BakeKey bakeKey = new io.tessera.core.BakeKey(key, tint);
        if (!registry.has(bakeKey)) return; // Skip; post-break path will handle (possibly via runtime bake).
        if (active.get() >= cfg.maxConcurrentFakeBlocks()) {
            if (cfg.debug()) plugin.getLogger().info(
                    "[debug-progress] skip " + bakeKey + " at " + posKey + " (concurrency cap)");
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

        Vector eyeDir = player.getEyeLocation().getDirection();
        active.incrementAndGet();
        FakeBlock fb;
        try {
            fb = factory.create(breakLoc, bakeKey, blockRotation, cfg.fillInterior(), eyeDir);
        } catch (RuntimeException re) {
            active.decrementAndGet();
            plugin.getLogger().warning("Failed to spawn FakeBlock for " + bakeKey + ": " + re.getMessage());
            return;
        }

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
        double targetProgress;
        int interpTicks;
        if (cfg.smoothInterpolation()) {
            // Lerp toward where we expect to be at the next event so the
            // 0.1-progress steps don't look chopped. The expected gap comes
            // from the inter-event EMA once we have data, or the seeded
            // estimate from Block#getBreakSpeed otherwise.
            long gapMs = tb.avgEventGapMs > 0L ? tb.avgEventGapMs : tb.initialGapEstimateMs;
            if (gapMs <= 0L) gapMs = MS_PER_TICK;
            if (gapMs > MAX_INTERP_MS) gapMs = MAX_INTERP_MS;
            interpTicks = Math.max(1, (int) Math.round((double) gapMs / MS_PER_TICK));
            targetProgress = Math.min(PREDICTIVE_TARGET_MAX, progress + STAGE_PER_EVENT);
        } else {
            targetProgress = progress;
            interpTicks = FORWARD_INTERP_TICKS_FALLBACK;
        }
        DirectionalShrinkEffect.applyAtProgress(tb.fakeBlock, tb.chunkT, tb.baseScales, tb.currentScales,
                targetProgress, cfg.waveWindow(), interpTicks, cfg.progressMinDelta(), cfg.collapseStyle());
        tb.lastAppliedProgress = progress;
        tb.lastUpdateTickMs = System.currentTimeMillis();
        maybeForceBreak(tb, progress, cfg);
    }

    /**
     * If we hid the real block as BARRIER, the client refuses to send the
     * "destroy completed" packet for it, so vanilla never finalises the break
     * and {@link org.bukkit.event.block.BlockBreakEvent} never fires. Once
     * progress reaches 1.0 we drive the break ourselves via
     * {@link Player#breakBlock(Block)} — that fires BlockBreakEvent
     * (which our {@link BlockBreakListener} routes back through
     * {@link #onRealBreak}), respects enchantments, and produces tool-correct
     * drops. The {@code autoBreakTriggered} flag keeps it idempotent.
     */
    private void maybeForceBreak(TrackedBreak tb, double progress, TesseraConfig cfg) {
        if (tb.autoBreakTriggered) return;
        if (!tb.barrierSent) return;       // vanilla mining will finish on its own
        if (progress < 0.999d) return;
        Player player = Bukkit.getPlayer(tb.currentPlayerId);
        if (player == null) return;
        Block worldBlock = player.getWorld().getBlockAt(
                tb.origin.getBlockX(), tb.origin.getBlockY(), tb.origin.getBlockZ());
        tb.autoBreakTriggered = true;
        if (cfg.debug()) plugin.getLogger().info(
                "[debug-progress] force-break " + tb.key + " at " + worldBlock.getLocation()
                        + " by=" + player.getName());
        // Fires BlockBreakEvent synchronously; our BlockBreakListener.onBreak
        // sees the active tracker and disposes it via onRealBreak.
        player.breakBlock(worldBlock);
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
                    rp, cfg.waveWindow(), REVERSE_INTERP_TICKS, cfg.progressMinDelta(), cfg.collapseStyle());
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
            if (tb.state == State.FORWARD && (now - tb.lastUpdateTickMs) > staleThresholdFor(tb, cfg)) {
                stale.add(e.getKey());
            }
        }
        for (BlockPosKey k : stale) {
            TrackedBreak tb = tracker.get(k);
            if (tb == null) continue;
            if (cfg.debug()) plugin.getLogger().info(
                    "[debug-progress] watchdog-cancel " + tb.key + " at " + k
                            + " stale=" + (now - tb.lastUpdateTickMs) + "ms"
                            + " threshold=" + staleThresholdFor(tb, cfg) + "ms"
                            + " avgGap=" + tb.avgEventGapMs + "ms"
                            + " speculative=" + tb.speculative);
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
