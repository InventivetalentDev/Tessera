package org.inventivetalent.tessera.plugin;

import io.papermc.paper.event.block.BlockBreakProgressUpdateEvent;
import org.inventivetalent.tessera.assemble.BlockGeometry;
import org.inventivetalent.tessera.assemble.FakeBlockFactory;
import org.inventivetalent.tessera.core.*;
import org.inventivetalent.tessera.effect.ChunkWaveSampler;
import org.inventivetalent.tessera.effect.builtin.DirectionalShrinkEffect;
import org.inventivetalent.tessera.nms.BlockTintReader;
import org.inventivetalent.tessera.plugin.ProgressTracker.BlockPosKey;
import org.inventivetalent.tessera.plugin.ProgressTracker.State;
import org.inventivetalent.tessera.plugin.ProgressTracker.TrackedBreak;
import org.inventivetalent.tessera.plugin.TesseraConfig.AnimationMode;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.inventivetalent.tessera.transport.TransportSession;
import org.bukkit.*;
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
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long WATCHDOG_STALE_BOOTSTRAP_MS = 2500L;
    private static final long WATCHDOG_STALE_MIN_MS = 800L;
    private static final long WATCHDOG_STALE_MAX_MS = 4000L;
    private static final int FORWARD_INTERP_TICKS_FALLBACK = 1;
    private static final int REVERSE_INTERP_TICKS = 1;
    private static final long MS_PER_TICK = 50L;
    private static final double STAGE_PER_EVENT = 0.1d;
    private static final double PREDICTIVE_TARGET_MAX = 0.95d;
    private static final long MAX_INTERP_MS = 800L;
    // Max chunks spawned per tick from the pending list. Spreading across ticks
    // via runTaskLater avoids tick spikes when large numbers of chunks become
    // eligible simultaneously (e.g. all interior chunks at gridN=16).
    private static final int PENDING_BATCH_SIZE = 128;
    /**
     * Min interval between replayed block-hit sounds. Vanilla plays a hit
     * sound every 4 ticks (200ms) while mining; we match that so the
     * barrier mask doesn't substitute its "stone hit" for the real block's.
     */
    private static final long HIT_SOUND_MIN_INTERVAL_MS = 200L;

    /**
     * Front-facing chunks pre-spawned while the player aims at a block.
     * Only the viewer-facing faces are pre-spawned; the back faces are spawned on click.
     */
    private record PreloadEntry(
            BlockPosKey posKey,
            BakeKey bakeKey,
            Quaternionf blockRotation,
            Map<ChunkCoord, ChunkRef> prespawnedChunks,
            TransportSession session
    ) {}

    private final TesseraPlugin plugin;
    private final FakeBlockFactory factory;
    private final HeadsRegistry registry;
    private final AtomicInteger active;
    private final ProgressTracker tracker;
    private BukkitTask watchdog;
    private BukkitTask aimWatchTask;
    private final Map<UUID, PreloadEntry> preloads = new ConcurrentHashMap<>();

    public BlockBreakProgressListener(TesseraPlugin plugin, FakeBlockFactory factory,
                                      HeadsRegistry registry, AtomicInteger active,
                                      ProgressTracker tracker) {
        this.plugin = plugin;
        this.factory = factory;
        this.registry = registry;
        this.active = active;
        this.tracker = tracker;
    }

    /** Start the watchdog and aim-watch timers. Called from plugin onEnable after registration. */
    public void start() {
        this.watchdog = Bukkit.getScheduler().runTaskTimer(
                plugin, this::watchdogTick, WATCHDOG_PERIOD_TICKS, WATCHDOG_PERIOD_TICKS);
        this.aimWatchTask = Bukkit.getScheduler().runTaskTimer(
                plugin, this::aimWatchTick, 2L, 2L);
    }

    /** Stop timers and tear down all in-flight trackers and preloads. Called on plugin disable. */
    public void shutdown() {
        if (watchdog != null) {
            watchdog.cancel();
            watchdog = null;
        }
        if (aimWatchTask != null) {
            aimWatchTask.cancel();
            aimWatchTask = null;
        }
        new ArrayList<>(preloads.keySet()).forEach(this::clearPreload);
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

        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] progress " + posKey + " player=" + player.getName()
                        + " progress=" + progress);

        TrackedBreak tb = tracker.get(posKey);

        if (tb == null) {
            if (progress <= 0d) return;
            spawnAndRegister(player, block, breakLoc, posKey, progress, cfg);
            return;
        }

        if (!tb.currentPlayerId.equals(player.getUniqueId())) {
            if (progress <= tb.lastAppliedProgress) return;
            transferOwnership(tb, posKey, player, progress, cfg);
            return;
        }

        if (progress <= 0d) {
            if (tb.speculative || tb.lastAppliedProgress <= 0d) {
                tb.lastUpdateTickMs = System.currentTimeMillis();
                return;
            }
            beginReverse(tb, posKey, cfg);
            return;
        }

        if (tb.state == State.REVERSING) {
            cancelReverseTask(tb);
            tb.state = State.FORWARD;
            if (cfg.clientHideRealBlock() && !tb.barrierSent) {
                if (!tb.shellExpanded) {
                    DirectionalShrinkEffect.rescaleShell(
                            tb.fakeBlock, tb.baseScales, tb.currentScales,
                            1f / FakeBlockFactory.INITIAL_SHELL_COMPRESSION, 0, tb.baseTranslations);
                    tb.shellExpanded = true;
                }
                try {
                    player.sendBlockChange(breakLoc, Material.BARRIER.createBlockData());
                    tb.barrierSent = true;
                } catch (RuntimeException re) {
                    plugin.getLogger().warning("sendBlockChange(BARRIER) on resume failed: " + re.getMessage());
                }
            }
        }

        if (tb.speculative) {
            tb.speculative = false;
            if (cfg.debug()) plugin.getLogger().info(
                    "[" + ts() + "] [debug-progress] speculative-confirmed " + tb.key + " at " + posKey
                            + " progress=" + fmt(progress)
                            + " barrierSent=" + tb.barrierSent
                            + " shellExpanded=" + tb.shellExpanded);
        }

        long now = System.currentTimeMillis();
        long gap = now - tb.lastUpdateTickMs;
        tb.avgEventGapMs = (tb.avgEventGapMs == 0L) ? gap : (tb.avgEventGapMs * 3L + gap) / 4L;
        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] update-gap " + posKey + " player=" + player.getName()
                        + " gap=" + gap + " avg=" + tb.avgEventGapMs);

        if (Math.abs(progress - tb.lastAppliedProgress) < cfg.progressMinDelta()) {
            tb.lastUpdateTickMs = now;
            return;
        }

        playBlockHitSound(tb, player);
        applyForward(tb, progress, cfg);
    }

    /**
     * Speculative pre-spawn on left-click. Vanilla's first
     * BlockBreakProgressUpdateEvent arrives only when the client crosses the
     * 0.1 destroy-stage boundary, which on a default-speed mine is ~600ms
     * after the player started swinging. Catching the click lets us spawn
     * immediately so the animation actually starts when the player starts
     * mining, not 600ms in.
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

        if (tooFast(block, player, cfg)) return;

        Location breakLoc = block.getLocation();
        BlockPosKey posKey = BlockPosKey.of(breakLoc);
        if (tracker.get(posKey) != null) return;

        if (cfg.eagerPreload()) {
            PreloadEntry preload = consumePreload(player.getUniqueId(), posKey);
            if (preload != null) {
                if (active.get() >= cfg.maxConcurrentFakeBlocks()) {
                    preload.session().close();
                    return;
                }
                BlockPosKey prev = tracker.activeBlockFor(player.getUniqueId());
                if (prev != null && !prev.equals(posKey)) {
                    TrackedBreak old = tracker.remove(prev);
                    if (old != null) disposeImmediate(old, /*restoreBlock=*/ true);
                }
                active.incrementAndGet();
                Vector eyeDir = player.getEyeLocation().getDirection();
                FakeBlockFactory.PreloadPlan plan;
                try {
                    plan = factory.completePlan(breakLoc, preload.bakeKey(),
                            preload.blockRotation(), cfg.fillInterior(), eyeDir,
                            preload.prespawnedChunks(), preload.session());
                } catch (RuntimeException re) {
                    preload.session().close();
                    active.decrementAndGet();
                    return;
                }
                int gridN = registry.gridN();
                Location origin = new Location(breakLoc.getWorld(),
                        Math.floor(breakLoc.getX()), Math.floor(breakLoc.getY()), Math.floor(breakLoc.getZ()));
                FakeBlock fb = new FakeBlock(origin, preload.bakeKey().block(), gridN,
                        new ArrayList<>(plan.frontRefs().values()), preload.blockRotation(), plan.session());
                TrackedBreak tb = buildTrackedBreak(player.getUniqueId(),
                        preload.bakeKey().block(), fb, block.getBlockData(), eyeDir, plan);
                tracker.put(posKey, tb);
                tb.speculative = true;
                tb.initialGapEstimateMs = estimateInitialGapMs(block, player, cfg);
                tb.lastUpdateTickMs = System.currentTimeMillis();
                if (cfg.clientHideRealBlock()) {
                    DirectionalShrinkEffect.rescaleShell(
                            tb.fakeBlock, tb.baseScales, tb.currentScales,
                            1f / FakeBlockFactory.INITIAL_SHELL_COMPRESSION, 0, tb.baseTranslations);
                    tb.shellExpanded = true;
                    try {
                        player.sendBlockChange(breakLoc, Material.BARRIER.createBlockData());
                        if (cfg.debug()) plugin.getLogger().info(
                                "[" + ts() + "] [debug-progress] sent-barrier");
                        tb.barrierSent = true;
                    } catch (RuntimeException re) {
                        plugin.getLogger().warning("sendBlockChange(BARRIER) eager failed: " + re.getMessage());
                    }
                }
                if (cfg.debug()) plugin.getLogger().info(
                        "[" + ts() + "] [debug-progress] eager-consume " + preload.bakeKey()
                                + " at " + posKey + " player=" + player.getName()
                                + " barrierSent=" + tb.barrierSent
                                + " front=" + plan.frontRefs().size()
                                + " pending=" + plan.pendingSpecs().size());
                playBlockHitSound(tb, player);
                applyForward(tb, STAGE_PER_EVENT, cfg);
                return;
            }
        }

        spawnAndRegister(player, block, breakLoc, posKey, 0d, cfg);
        TrackedBreak tb = tracker.get(posKey);
        if (tb == null) return;
        tb.speculative = true;
        tb.initialGapEstimateMs = estimateInitialGapMs(block, player, cfg);
        tb.lastUpdateTickMs = System.currentTimeMillis();
        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] speculative-spawn at " + posKey
                        + " player=" + player.getName()
                        + " initialGapMs=" + tb.initialGapEstimateMs);
        playBlockHitSound(tb, player);
    }

    private static long staleThresholdFor(TrackedBreak tb, TesseraConfig cfg) {
        if (tb.speculative) {
            long grace = Math.max(100L, cfg.leftClickGraceMs());
            if (tb.initialGapEstimateMs > 0L) return Math.max(grace, tb.initialGapEstimateMs + 200L);
            return grace;
        }
        if (tb.avgEventGapMs == 0L) return WATCHDOG_STALE_BOOTSTRAP_MS;
        long base = tb.avgEventGapMs * 3L + 200L;
        if (base < WATCHDOG_STALE_MIN_MS) return WATCHDOG_STALE_MIN_MS;
        return Math.min(base, WATCHDOG_STALE_MAX_MS);
    }

    private static long estimateInitialGapMs(Block block, Player player, TesseraConfig cfg) {
        try {
            float perTick = block.getBreakSpeed(player);
            if (perTick > 0f) {
                long perStageMs = (long) Math.ceil(STAGE_PER_EVENT / perTick * MS_PER_TICK);
                if (perStageMs < MS_PER_TICK) return MS_PER_TICK;
                return Math.min(perStageMs, MAX_INTERP_MS);
            }
        } catch (NoSuchMethodError | RuntimeException ignored) {}
        return Math.clamp(cfg.leftClickGraceMs(), MS_PER_TICK, MAX_INTERP_MS);
    }

    /**
     * Estimates total break duration in milliseconds from {@code Block.getBreakSpeed}.
     * Returns {@link Long#MAX_VALUE} when the speed is unavailable (assume slow enough).
     */
    static long estimateBreakDurationMs(Block block, Player player) {
        try {
            float perTick = block.getBreakSpeed(player);
            if (perTick > 0f) return (long) Math.ceil(1.0 / (double) perTick * MS_PER_TICK);
        } catch (NoSuchMethodError | RuntimeException ignored) {}
        return Long.MAX_VALUE;
    }

    /** Returns true when the block is estimated to break too fast for the animation to be worthwhile. */
    private static boolean tooFast(Block block, Player player, TesseraConfig cfg) {
        int min = cfg.minBreakDurationMs();
        return min > 0 && estimateBreakDurationMs(block, player) < min;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clearPreload(id);
        teardownPlayer(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clearPreload(id);
        teardownPlayer(id);
    }

    private void spawnAndRegister(Player player, Block block, Location breakLoc,
                                  BlockPosKey posKey, double progress, TesseraConfig cfg) {
        Material mat = block.getType();
        BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());
        if (!cfg.enables(key.asString())) return;
        if (tooFast(block, player, cfg)) return;
        int tint = cfg.enableTintedBlocks() ? BlockTintReader.read(block) : 0;
        BakeKey bakeKey = new BakeKey(key, tint);
        if (!registry.has(bakeKey)) return;
        if (active.get() >= cfg.maxConcurrentFakeBlocks()) {
            if (cfg.debug()) plugin.getLogger().info(
                    "[debug-progress] skip " + bakeKey + " at " + posKey + " (concurrency cap)");
            return;
        }

        BlockPosKey prev = tracker.activeBlockFor(player.getUniqueId());
        if (prev != null && !prev.equals(posKey)) {
            TrackedBreak old = tracker.remove(prev);
            if (old != null) disposeImmediate(old, /*restoreBlock=*/ true);
        }

        BlockData blockData = block.getBlockData();
        String fullStateKey = VariantKey.fromBlockData(blockData);
        String matchedKey = VariantKey.pickMatching(fullStateKey, registry.variantsFor(key).keySet());
        Quaternionf blockRotation = registry.rotationFor(key, matchedKey);

        Vector eyeDir = player.getEyeLocation().getDirection();
        active.incrementAndGet();

        FakeBlockFactory.PreloadPlan plan;
        try {
            plan = factory.preloadAndPending(player, breakLoc, bakeKey, blockRotation,
                    cfg.fillInterior(), eyeDir);
        } catch (RuntimeException re) {
            active.decrementAndGet();
            plugin.getLogger().warning("Failed to spawn FakeBlock for " + bakeKey + ": " + re.getMessage());
            return;
        }

        int gridN = registry.gridN();
        Location origin = new Location(breakLoc.getWorld(),
                Math.floor(breakLoc.getX()), Math.floor(breakLoc.getY()), Math.floor(breakLoc.getZ()));
        FakeBlock fb = new FakeBlock(origin, key, gridN,
                new ArrayList<>(plan.frontRefs().values()), blockRotation, plan.session());

        TrackedBreak tb = buildTrackedBreak(player.getUniqueId(), key, fb, blockData, eyeDir, plan);
        tracker.put(posKey, tb);

        clearPreloadsAt(posKey);

        if (cfg.clientHideRealBlock()) {
            sendBarrierNow(tb, player, breakLoc);
        }

        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] spawn " + key + " at " + posKey
                        + " player=" + player.getName() + " progress=" + fmt(progress)
                        + " barrierSent=" + tb.barrierSent
                        + " front=" + plan.frontRefs().size()
                        + " pending=" + plan.pendingSpecs().size());

        applyForward(tb, progress > 0d ? progress : STAGE_PER_EVENT, cfg);
    }

    private void applyForward(TrackedBreak tb, double progress, TesseraConfig cfg) {
        cancelSmoothTask(tb);

        double targetProgress;
        int interpTicks;
        if (cfg.smoothInterpolation()) {
            long gapMs = tb.avgEventGapMs > 0L ? tb.avgEventGapMs : tb.initialGapEstimateMs;
            if (gapMs <= 0L) gapMs = MS_PER_TICK;
            if (gapMs > MAX_INTERP_MS) gapMs = MAX_INTERP_MS;
            targetProgress = Math.min(PREDICTIVE_TARGET_MAX, progress + STAGE_PER_EVENT);
            interpTicks = 2;
            scheduleSmoothAdvance(tb, progress, targetProgress, gapMs, cfg);
        } else {
            targetProgress = progress;
            interpTicks = FORWARD_INTERP_TICKS_FALLBACK;
        }
        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] applyForward targetProgress=" + fmt(targetProgress));

        spawnPendingChunks(tb, targetProgress, cfg.waveWindow());
        boolean firstReal = tb.lastAppliedProgress <= 0d && progress > 0d;
        // Snap to the confirmed progress; smooth task will advance toward targetProgress.
        double immediateTarget = cfg.smoothInterpolation() ? progress : targetProgress;
        DirectionalShrinkEffect.applyAtProgress(tb.fakeBlock, tb.chunkT, tb.baseScales, tb.currentScales,
                immediateTarget, cfg.waveWindow(), interpTicks, cfg.progressMinDelta(), cfg.collapseStyle(),
                tb.recedeDelta, tb.baseTranslations);
        tb.lastAppliedProgress = progress;
        tb.lastUpdateTickMs = System.currentTimeMillis();
        DirectionalShrinkEffect.despawnPassedChunks(tb.fakeBlock, tb.chunkT,
                tb.baseScales, tb.currentScales,
                progress, cfg.waveWindow(), (float) cfg.progressMinDelta());
        if (firstReal && cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] first-real-progress " + tb.key
                        + " progress=" + fmt(progress) + " target=" + fmt(targetProgress)
                        + " interpTicks=" + interpTicks
                        + " barrierSent=" + tb.barrierSent
                        + " shellExpanded=" + tb.shellExpanded);
        maybeForceBreak(tb, progress, cfg);
    }

    private void scheduleSmoothAdvance(TrackedBreak tb, double fromProgress, double toProgress,
                                       long durationMs, TesseraConfig cfg) {
        final long startMs = System.currentTimeMillis();
        tb.smoothAdvanceTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (tb.state != State.FORWARD || tb.fakeBlock.despawned()) {
                cancelSmoothTask(tb);
                return;
            }
            long elapsed = System.currentTimeMillis() - startMs;
            if (elapsed >= durationMs) {
                cancelSmoothTask(tb);
                return;
            }
            double fraction = (double) elapsed / durationMs;
            double interpProgress = fromProgress + fraction * (toProgress - fromProgress);
            spawnPendingChunks(tb, interpProgress, cfg.waveWindow());
            DirectionalShrinkEffect.applyAtProgress(tb.fakeBlock, tb.chunkT, tb.baseScales, tb.currentScales,
                    interpProgress, cfg.waveWindow(), 2, cfg.progressMinDelta(), cfg.collapseStyle(),
                    tb.recedeDelta, tb.baseTranslations);
            DirectionalShrinkEffect.despawnPassedChunks(tb.fakeBlock, tb.chunkT, tb.baseScales, tb.currentScales,
                    interpProgress, cfg.waveWindow(), (float) cfg.progressMinDelta());
            tb.lastUpdateTickMs = System.currentTimeMillis();
        }, 1L, 1L);
    }

    private void maybeForceBreak(TrackedBreak tb, double progress, TesseraConfig cfg) {
        if (tb.autoBreakTriggered) return;
        if (!tb.barrierSent) return;
        if (progress < 0.999d) return;
        Player player = Bukkit.getPlayer(tb.currentPlayerId);
        if (player == null) return;
        Block worldBlock = player.getWorld().getBlockAt(
                tb.origin.getBlockX(), tb.origin.getBlockY(), tb.origin.getBlockZ());
        tb.autoBreakTriggered = true;
        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] force-break " + tb.key + " at " + worldBlock.getLocation()
                        + " by=" + player.getName());
        // Play the original block's break sound to the breaker before invoking
        // breakBlock. The breakBlock call destroys the world block and broadcasts
        // LevelEvent 2001, but with the barrier mask up the breaker's client may
        // mispredict / dedupe that sound; doing it explicitly here guarantees the
        // breaker hears the right sound. Mark the flag so onRealBreak (invoked
        // synchronously by breakBlock's BlockBreakEvent) doesn't double-play.
        playBlockBreakSound(tb, player);
        tb.breakSoundPlayed = true;
        if (!player.breakBlock(worldBlock)) {
            BlockPosKey posKey = BlockPosKey.of(tb.origin);
            TrackedBreak still = tracker.remove(posKey);
            if (still == tb) {
                disposeImmediate(tb, /*restoreBlock=*/ false);
                clearPreloadsAt(posKey);
            }
        }
    }

    private void transferOwnership(TrackedBreak tb, BlockPosKey posKey, Player newPlayer,
                                   double progress, TesseraConfig cfg) {
        UUID oldId = tb.currentPlayerId;
        Player oldPlayer = Bukkit.getPlayer(oldId);
        if (oldPlayer != null && tb.barrierSent) {
            try {
                oldPlayer.sendBlockChange(tb.origin, tb.originalBlockData);
            } catch (RuntimeException ignored) {}
        }
        tb.currentPlayerId = newPlayer.getUniqueId();
        tb.barrierSent = false;
        tb.eyeDir = newPlayer.getEyeLocation().getDirection();
        cancelSmoothTask(tb);
        cancelPendingSpawnTask(tb);
        recomputeAllT(tb, tb.eyeDir);
        tracker.transferOwner(posKey, oldId, newPlayer.getUniqueId());

        if (cfg.clientHideRealBlock()) {
            sendBarrierNow(tb, newPlayer, tb.origin);
        }

        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] transfer " + tb.key + " at " + posKey
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
        cancelSmoothTask(tb);
        cancelPendingSpawnTask(tb);
        tb.state = State.REVERSING;
        final double startProgress = tb.lastAppliedProgress;
        final long startMs = System.currentTimeMillis();
        final int durationMs = (int) Math.clamp(Math.round(startProgress * 300d), 50L, 300L);

        if (tb.barrierSent) {
            Player p = Bukkit.getPlayer(tb.currentPlayerId);
            if (p != null) {
                try { p.sendBlockChange(tb.origin, tb.originalBlockData); }
                catch (RuntimeException ignored) {}
            }
            tb.barrierSent = false;
        }
        if (tb.shellExpanded) {
            DirectionalShrinkEffect.rescaleShell(
                    tb.fakeBlock, tb.baseScales, tb.currentScales,
                    FakeBlockFactory.INITIAL_SHELL_COMPRESSION, 0, tb.baseTranslations);
            tb.shellExpanded = false;
        }

        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] reverse-start " + tb.key + " at " + posKey
                        + " from=" + fmt(startProgress) + " durationMs=" + durationMs);

        tb.reverseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            TrackedBreak live = tracker.get(posKey);
            if (live != tb || live.state != State.REVERSING) {
                cancelReverseTask(tb);
                return;
            }
            long elapsed = System.currentTimeMillis() - startMs;
            double rp = elapsed >= durationMs ? 0d
                    : startProgress * (1.0 - (double) elapsed / (double) durationMs);
            DirectionalShrinkEffect.applyAtProgress(tb.fakeBlock, tb.chunkT, tb.baseScales, tb.currentScales,
                    rp, cfg.waveWindow(), REVERSE_INTERP_TICKS, cfg.progressMinDelta(), cfg.collapseStyle(),
                    tb.recedeDelta, tb.baseTranslations);
            tb.lastAppliedProgress = rp;
            tb.lastUpdateTickMs = System.currentTimeMillis();
            if (rp <= 0d) {
                tracker.remove(posKey);
                cancelReverseTask(tb);
                disposeImmediate(tb, /*restoreBlock=*/ true);
                if (cfg.debug()) plugin.getLogger().info(
                        "[" + ts() + "] [debug-progress] reverse-done " + tb.key + " at " + posKey);
            }
        }, 1L, 1L);
    }

    void onRealBreak(BlockPosKey posKey) {
        TrackedBreak tb = tracker.remove(posKey);
        if (tb == null) return;
        cancelReverseTask(tb);
        if (tb.barrierSent) {
            Player p = Bukkit.getPlayer(tb.currentPlayerId);
            if (p != null) {
                try { p.sendBlockChange(tb.origin, Material.AIR.createBlockData()); }
                catch (RuntimeException ignored) {}
                if (!tb.breakSoundPlayed) {
                    playBlockBreakSound(tb, p);
                    tb.breakSoundPlayed = true;
                }
            }
            tb.barrierSent = false;
        }
        disposeImmediate(tb, /*restoreBlock=*/ false);
        clearPreloadsAt(posKey);
        if (plugin.tesseraConfig().debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] real-break " + tb.key + " at " + posKey);
    }

    public boolean isTracked(BlockPosKey posKey) {
        return tracker.get(posKey) != null;
    }

    private void teardownPlayer(UUID playerId) {
        BlockPosKey posKey = tracker.activeBlockFor(playerId);
        if (posKey == null) return;
        TrackedBreak tb = tracker.remove(posKey);
        if (tb == null) return;
        cancelReverseTask(tb);
        disposeImmediate(tb, false);
        if (plugin.tesseraConfig().debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] teardown player=" + playerId + " posKey=" + posKey);
    }

    private void watchdogTick() {
        long now = System.currentTimeMillis();
        TesseraConfig cfg = plugin.tesseraConfig();
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
                    "[" + ts() + "] [debug-progress] watchdog-cancel " + tb.key + " at " + k
                            + " stale=" + (now - tb.lastUpdateTickMs) + "ms"
                            + " threshold=" + staleThresholdFor(tb, cfg) + "ms"
                            + " avgGap=" + tb.avgEventGapMs + "ms"
                            + " speculative=" + tb.speculative);
            beginReverse(tb, k, cfg);
        }
    }

    private void disposeImmediate(TrackedBreak tb, boolean restoreBlock) {
        cancelSmoothTask(tb);
        cancelPendingSpawnTask(tb);
        if (restoreBlock && tb.barrierSent) {
            Player p = Bukkit.getPlayer(tb.currentPlayerId);
            if (p != null) {
                try { p.sendBlockChange(tb.origin, tb.originalBlockData); }
                catch (RuntimeException ignored) {}
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

    private static void cancelSmoothTask(TrackedBreak tb) {
        if (tb.smoothAdvanceTask != null) {
            try { tb.smoothAdvanceTask.cancel(); } catch (RuntimeException ignored) {}
            tb.smoothAdvanceTask = null;
        }
    }

    private static void cancelPendingSpawnTask(TrackedBreak tb) {
        if (tb.pendingSpawnTask != null) {
            try { tb.pendingSpawnTask.cancel(); } catch (RuntimeException ignored) {}
            tb.pendingSpawnTask = null;
        }
    }

    /**
     * Replay the original block's mining-hit sound for the breaker. The
     * client otherwise plays the BARRIER hit sound while the mask is up.
     * Volume/pitch follow vanilla's {@code LevelRenderer.hitBlockEffect}:
     * {@code (vol+1)/8}, {@code pitch*0.5}.
     */
    private void playBlockHitSound(TrackedBreak tb, Player player) {
        if (!tb.barrierSent) return;
        long now = System.currentTimeMillis();
        if (now - tb.lastHitSoundMs < HIT_SOUND_MIN_INTERVAL_MS) return;
        SoundGroup group;
        try {
            group = tb.originalBlockData.getSoundGroup();
        } catch (RuntimeException re) {
            return;
        }
        Sound hit = group.getHitSound();
        if (hit == null) return;
        Location at = tb.origin.clone().add(0.5, 0.5, 0.5);
        try {
            player.playSound(at, hit, SoundCategory.BLOCKS,
                    (group.getVolume() + 1f) / 8f, group.getPitch() * 0.5f);
            tb.lastHitSoundMs = now;
        } catch (RuntimeException ignored) {}
    }

    /**
     * Replay the original block's break sound for the breaker. Vanilla
     * normally broadcasts LevelEvent 2001 with the original block's
     * stateId on real-break, but the BARRIER mask we sent earlier can
     * cause the client to dedupe / mispredict the sound; an explicit
     * play guarantees the right one is heard. Volume/pitch follow
     * vanilla's {@code Level.destroyBlock} → LevelEvent handler:
     * {@code (vol+1)/2}, {@code pitch*0.8}.
     */
    private void playBlockBreakSound(TrackedBreak tb, Player player) {
        SoundGroup group;
        try {
            group = tb.originalBlockData.getSoundGroup();
        } catch (RuntimeException re) {
            return;
        }
        Sound brk = group.getBreakSound();
        if (brk == null) return;
        Location at = tb.origin.clone().add(0.5, 0.5, 0.5);
        try {
            player.playSound(at, brk, SoundCategory.BLOCKS,
                    (group.getVolume() + 1f) / 2f, group.getPitch() * 0.8f);
        } catch (RuntimeException ignored) {}
    }

    private void sendBarrierNow(TrackedBreak tb, Player player, Location loc) {
        if (tb.barrierSent) return;
        if (!tb.shellExpanded) {
            DirectionalShrinkEffect.rescaleShell(
                    tb.fakeBlock, tb.baseScales, tb.currentScales,
                    1f / FakeBlockFactory.INITIAL_SHELL_COMPRESSION, 0, tb.baseTranslations);
            tb.shellExpanded = true;
        }
        try {
            player.sendBlockChange(loc, Material.BARRIER.createBlockData());
            tb.barrierSent = true;
            if (plugin.tesseraConfig().debug()) plugin.getLogger().info(
                    "[" + ts() + "] [debug-progress] barrier-send " + tb.key
                            + " player=" + player.getName()
                            + " progress=" + fmt(tb.lastAppliedProgress));
        } catch (RuntimeException re) {
            plugin.getLogger().warning("sendBlockChange(BARRIER) failed: " + re.getMessage());
        }
    }

    // ── Eager preload helpers ────────────────────────────────────────────────

    private void aimWatchTick() {
        TesseraConfig cfg = plugin.tesseraConfig();
        if (!cfg.eagerPreload()) {
            if (!preloads.isEmpty()) new ArrayList<>(preloads.keySet()).forEach(this::clearPreload);
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getGameMode() != GameMode.SURVIVAL) {
                clearPreload(player.getUniqueId());
                continue;
            }
            Block target;
            try {
                target = player.getTargetBlockExact(5, FluidCollisionMode.NEVER);
            } catch (RuntimeException ignored) {
                clearPreload(player.getUniqueId());
                continue;
            }
            BlockPosKey targetKey = (target != null && !target.getType().isAir())
                    ? BlockPosKey.of(target.getLocation()) : null;

            PreloadEntry existing = preloads.get(player.getUniqueId());
            if (existing != null) {
                if (existing.posKey.equals(targetKey)) {
                    if (tracker.get(targetKey) == null) continue;
                    clearPreload(player.getUniqueId());
                } else {
                    clearPreload(player.getUniqueId());
                }
            }
            if (targetKey == null) continue;
            if (tracker.get(targetKey) != null) continue;

            if (tooFast(target, player, cfg)) continue;

            Material mat = target.getType();
            BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());
            if (!cfg.enables(key.asString())) continue;
            int tint = cfg.enableTintedBlocks() ? BlockTintReader.read(target) : 0;
            BakeKey bakeKey = new BakeKey(key, tint);
            if (!registry.has(bakeKey)) continue;

            BlockData blockData = target.getBlockData();
            String fullStateKey = VariantKey.fromBlockData(blockData);
            String matchedKey = VariantKey.pickMatching(fullStateKey, registry.variantsFor(key).keySet());
            Quaternionf blockRotation = registry.rotationFor(key, matchedKey);
            Vector eyeDir = player.getEyeLocation().getDirection();

            FakeBlockFactory.PreloadResult preloadResult;
            try {
                preloadResult = factory.preload(player, target.getLocation(), bakeKey, blockRotation, eyeDir);
            } catch (RuntimeException re) {
                continue;
            }
            if (preloadResult.chunks().isEmpty()) continue;
            preloads.put(player.getUniqueId(),
                    new PreloadEntry(targetKey, bakeKey, blockRotation,
                            preloadResult.chunks(), preloadResult.session()));

            if (cfg.debug()) plugin.getLogger().info(
                    "[" + ts() + "] [debug-progress] eager-preload-spawn " + bakeKey
                            + " at " + targetKey + " for=" + player.getName()
                            + " chunks=" + preloadResult.chunks().size());
        }
    }

    private void clearPreload(UUID playerId) {
        PreloadEntry entry = preloads.remove(playerId);
        if (entry != null) entry.session().close();
    }

    void clearPreloadsAt(BlockPosKey posKey) {
        preloads.entrySet().removeIf(e -> {
            if (!e.getValue().posKey().equals(posKey)) return false;
            e.getValue().session().close();
            return true;
        });
    }

    private PreloadEntry consumePreload(UUID playerId, BlockPosKey posKey) {
        PreloadEntry entry = preloads.remove(playerId);
        if (entry == null) return null;
        if (!entry.posKey().equals(posKey)) {
            entry.session().close();
            return null;
        }
        return entry;
    }

    // ── Lazy-spawn helpers ───────────────────────────────────────────────────

    /**
     * Build a pre-allocated-array {@link TrackedBreak} from a {@link FakeBlockFactory.PreloadPlan}.
     * Arrays are sized for front + pending so the lazy-spawn lifecycle can fill slots
     * as the wave advances without reallocating.
     */
    private TrackedBreak buildTrackedBreak(UUID playerId, BlockKey key, FakeBlock fb,
                                           BlockData blockData, Vector eyeDir,
                                           FakeBlockFactory.PreloadPlan plan) {
        List<ChunkRef> front = fb.chunks();
        int frontCount = front.size();
        int pendingCount = plan.pendingSpecs().size();
        int total = frontCount + pendingCount;

        float[] base = new float[total];
        float[] current = new float[total];
        double[] chunkT = new double[total];
        Vector3f[] baseTrans = new Vector3f[total]; // pending slots stay null until spawned

        for (int i = 0; i < frontCount; i++) {
            ChunkRef cr = front.get(i);
            float s = cr.handle().getTransformation().getScale().x;
            base[i] = s;
            current[i] = s;
            chunkT[i] = plan.allOuterT().getOrDefault(cr.coord(), 0.0);
            baseTrans[i] = new Vector3f(cr.handle().getTransformation().getTranslation());
        }
        for (int i = 0; i < pendingCount; i++) {
            chunkT[frontCount + i] = plan.pendingSpecs().get(i).t();
        }

        Vector3f recedeDelta = DirectionalShrinkEffect.computeRecedeDelta(fb.gridN(), eyeDir);

        TrackedBreak tb = new TrackedBreak(playerId, key, fb.origin(),
                blockData, eyeDir, fb, chunkT, base, current, baseTrans, recedeDelta);
        tb.pendingChunks = new ArrayList<>(plan.pendingSpecs());
        return tb;
    }

    /**
     * Spawn pending chunks whose wave position is within one {@code window} of
     * {@code targetProgress}. At most {@link #PENDING_BATCH_SIZE} chunks are spawned
     * per call; a one-tick continuation is scheduled if eligible chunks remain.
     */
    private void spawnPendingChunks(TrackedBreak tb, double targetProgress, double window) {
        if (tb.pendingChunks == null || tb.pendingChunks.isEmpty()) return;
        if (tb.fakeBlock.despawned()) return;
        if (tb.pendingChunks.getFirst().t() > targetProgress + window) return;

        float shellFactor = tb.shellExpanded ? 1.0f : FakeBlockFactory.INITIAL_SHELL_COMPRESSION;
        int gridN = tb.fakeBlock.gridN();
        float chunkScale = 2f / gridN;

        FakeBlockFactory.PendingChunkSpec interiorDonor = null;
        for (FakeBlockFactory.PendingChunkSpec s : tb.pendingChunks) {
            if (s.t() > targetProgress + window) break;
            if (s.interior()) { interiorDonor = s; break; }
        }
        FakeBlockFactory.PendingSpawnContext ctx = factory.beginPendingBatch(tb.fakeBlock, interiorDonor);

        int spawned = 0;
        Iterator<FakeBlockFactory.PendingChunkSpec> it = tb.pendingChunks.iterator();
        while (it.hasNext() && spawned < PENDING_BATCH_SIZE) {
            FakeBlockFactory.PendingChunkSpec spec = it.next();
            if (spec.t() > targetProgress + window) break;
            it.remove();
            spawned++;

            ChunkRef ref;
            try {
                ref = factory.spawnPendingChunk(ctx, spec, shellFactor);
            } catch (RuntimeException re) {
                plugin.getLogger().warning(
                        "spawnPendingChunk failed for " + spec.coord() + ": " + re.getMessage());
                continue;
            }

            int idx = tb.fakeBlock.chunks().size();
            tb.fakeBlock.chunks().add(ref);

            tb.baseTranslations[idx] = new Vector3f(ref.handle().getTransformation().getTranslation());
            if (spec.interior()) {
                tb.baseScales[idx] = chunkScale;
                tb.currentScales[idx] = -1f; // sentinel: forces first applyAtProgress write
            } else {
                float baseAtSpawn = chunkScale * shellFactor;
                double sFraction = ChunkWaveSampler.shrunkFraction(spec.t(), targetProgress, window);
                float correctScale = Math.max(0f, (float) (baseAtSpawn * (1.0 - sFraction)));
                tb.baseScales[idx] = baseAtSpawn;
                tb.currentScales[idx] = correctScale;
                if (correctScale < baseAtSpawn) {
                    Transformation cur = ref.handle().getTransformation();
                    ref.handle().setTransformation(new Transformation(
                            cur.getTranslation(), cur.getLeftRotation(),
                            new Vector3f(correctScale, correctScale, correctScale),
                            cur.getRightRotation()), 0, 0);
                }
            }
        }

        if (tb.pendingSpawnTask == null && !tb.pendingChunks.isEmpty()
                && tb.pendingChunks.getFirst().t() <= targetProgress + window) {
            final double capturedTarget = targetProgress;
            final double capturedWindow = window;
            tb.pendingSpawnTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                tb.pendingSpawnTask = null;
                if (tb.fakeBlock.despawned() || tb.state != State.FORWARD) return;
                spawnPendingChunks(tb, capturedTarget, capturedWindow);
            }, 1L);
        }
    }

    /**
     * Recompute t values for all alive + pending chunks using a new eye direction.
     * Uses global min/max across both sets so spawned and pending chunks remain
     * on a consistent wave-position scale after an ownership transfer.
     */
    private void recomputeAllT(TrackedBreak tb, Vector newEyeDir) {
        BlockGeometry geom = new BlockGeometry(tb.fakeBlock.gridN(), tb.fakeBlock.blockRotation());
        Vector e = newEyeDir.clone().normalize();
        Vector3f dir = new Vector3f((float) e.getX(), (float) e.getY(), (float) e.getZ());
        Vector3f blockCenter = new Vector3f(0.5f, 0.5f, 0.5f);
        Quaternionf blockRot = tb.fakeBlock.blockRotation();

        List<ChunkRef> alive = tb.fakeBlock.chunks();
        List<FakeBlockFactory.PendingChunkSpec> pending =
                tb.pendingChunks != null ? tb.pendingChunks : List.of();

        int aliveCount = alive.size();
        int pendingCount = pending.size();
        double[] proj = new double[aliveCount + pendingCount];
        double minP = Double.POSITIVE_INFINITY, maxP = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < aliveCount; i++) {
            Vector3f rel = geom.chunkLocalCenter(alive.get(i).coord()).sub(blockCenter);
            new Quaternionf(blockRot).transform(rel);
            double p = rel.dot(dir);
            proj[i] = p;
            if (p < minP) minP = p;
            if (p > maxP) maxP = p;
        }
        for (int i = 0; i < pendingCount; i++) {
            Vector3f rel = geom.chunkLocalCenter(pending.get(i).coord()).sub(blockCenter);
            new Quaternionf(blockRot).transform(rel);
            double p = rel.dot(dir);
            proj[aliveCount + i] = p;
            if (p < minP) minP = p;
            if (p > maxP) maxP = p;
        }
        double range = Math.max(1e-6, maxP - minP);

        for (int i = 0; i < aliveCount; i++) {
            tb.chunkT[i] = (proj[i] - minP) / range;
        }
        if (!pending.isEmpty()) {
            List<FakeBlockFactory.PendingChunkSpec> rebuilt = new ArrayList<>(pendingCount);
            for (int i = 0; i < pendingCount; i++) {
                FakeBlockFactory.PendingChunkSpec old = pending.get(i);
                rebuilt.add(new FakeBlockFactory.PendingChunkSpec(
                        old.coord(), old.registryEntry(),
                        (proj[aliveCount + i] - minP) / range,
                        old.interior()));
            }
            rebuilt.sort(Comparator.comparingDouble(FakeBlockFactory.PendingChunkSpec::t));
            tb.pendingChunks = rebuilt;
            for (int i = 0; i < pendingCount; i++) {
                tb.chunkT[aliveCount + i] = rebuilt.get(i).t();
            }
        }

        tb.recedeDelta = DirectionalShrinkEffect.computeRecedeDelta(tb.fakeBlock.gridN(), newEyeDir);
    }

    private static String fmt(double d) { return String.format("%.3f", d); }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static String ts() { return LocalTime.now().format(TS_FMT); }
}
