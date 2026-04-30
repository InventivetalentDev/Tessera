package io.tessera.plugin;

import io.papermc.paper.event.block.BlockBreakProgressUpdateEvent;
import io.tessera.assemble.BlockGeometry;
import io.tessera.assemble.FakeBlockFactory;
import io.tessera.core.*;
import io.tessera.effect.ChunkWaveSampler;
import io.tessera.effect.builtin.DirectionalShrinkEffect;
import io.tessera.nms.BlockTintReader;
import io.tessera.plugin.ProgressTracker.BlockPosKey;
import io.tessera.plugin.ProgressTracker.State;
import io.tessera.plugin.ProgressTracker.TrackedBreak;
import io.tessera.plugin.TesseraConfig.AnimationMode;
import io.tessera.skin.HeadsRegistry;
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

    /**
     * Front-facing chunks pre-spawned while the player aims at a block.
     * Only the half with wave-position t &lt; {@link FakeBlockFactory#PRELOAD_T_THRESHOLD}
     * is pre-spawned; the remainder is spawned on click.
     */
    private record PreloadEntry(
            BlockPosKey posKey,
            BakeKey bakeKey,
            Quaternionf blockRotation,
            Map<ChunkCoord, ChunkRef> prespawnedChunks
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
        // Poll every 2 ticks for eager-preload; no-ops when the feature is off.
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
            // of every break. Only reverse if the speculative window has
            // closed (a real positive event has been confirmed) so there's
            // actually something to roll back. Using the speculative flag
            // rather than lastAppliedProgress <= 0 handles the case where a
            // jumpstart applyForward advances lastAppliedProgress before any
            // real event has arrived.
            if (tb.speculative || tb.lastAppliedProgress <= 0d) {
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
            // beginReverse restored the real block client-side and re-
            // compressed the lattice so it'd fit inside it. Undo both now
            // that we're committing to forward again. The entity is already
            // rendered (it's been visible throughout the reverse), so no
            // render-lag delay is needed — expand and re-hide in the same
            // tick.
            if (cfg.clientHideRealBlock() && !tb.barrierSent) {
                if (!tb.shellExpanded) {
                    DirectionalShrinkEffect.rescaleShell(
                            tb.fakeBlock, tb.baseScales, tb.currentScales,
                            1f / FakeBlockFactory.INITIAL_SHELL_COMPRESSION,
                            /*interpTicks=*/ 0);
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

        // First real progress event for a speculative spawn confirms the
        // player is actually mining. Drop the flag so the watchdog stops
        // using the (short) left-click grace window for staleness.
        if (tb.speculative) {
            tb.speculative = false;
            if (cfg.debug()) plugin.getLogger().info(
                    "[" + ts() + "] [debug-progress] speculative-confirmed " + tb.key + " at " + posKey
                            + " progress=" + fmt(progress)
                            + " barrierSent=" + tb.barrierSent
                            + " shellExpanded=" + tb.shellExpanded);
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
     * <p>If {@code interaction.eagerPreload} is enabled and the player was
     * already aiming at this block, the FakeBlock entity has been alive for
     * several ticks and is fully rendered. In that case we skip the
     * deferred barrier swap and send {@code BARRIER} immediately.
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

        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] onInteract");

        try {
            if (block.getBreakSpeed(player) >= 1.0f) return;
        } catch (NoSuchMethodError ignored) {}

        Location breakLoc = block.getLocation();
        BlockPosKey posKey = BlockPosKey.of(breakLoc);
        if (tracker.get(posKey) != null) return;

        // Eager-preload path: entity was pre-spawned while the player was
        // aiming, so it's already rendered. Consume the preload, create the
        // tracker, and swap to BARRIER immediately — no tick delay needed.
        if (cfg.eagerPreload()) {
            PreloadEntry preload = consumePreload(player.getUniqueId(), posKey);
            if (preload != null) {
                if (active.get() >= cfg.maxConcurrentFakeBlocks()) {
                    preload.prespawnedChunks().values().forEach(r -> {
                        if (!r.display().isDead()) r.display().remove();
                    });
                    return;
                }
                BlockPosKey prev = tracker.activeBlockFor(player.getUniqueId());
                if (prev != null && !prev.equals(posKey)) {
                    TrackedBreak old = tracker.remove(prev);
                    if (old != null) disposeImmediate(old, /*restoreBlock=*/ true);
                }
                active.incrementAndGet();
                Vector eyeDir = player.getEyeLocation().getDirection();
                // completePlan reuses the pre-spawned front entities and builds
                // a sorted pending list for the back-half + interior chunks.
                FakeBlockFactory.PreloadPlan plan;
                try {
                    plan = factory.completePlan(breakLoc, preload.bakeKey(),
                            preload.blockRotation(), cfg.fillInterior(), eyeDir,
                            preload.prespawnedChunks());
                } catch (RuntimeException re) {
                    preload.prespawnedChunks().values().forEach(r -> {
                        if (!r.display().isDead()) r.display().remove();
                    });
                    active.decrementAndGet();
                    return;
                }
                int gridN = registry.gridN();
                Location origin = new Location(breakLoc.getWorld(),
                        Math.floor(breakLoc.getX()), Math.floor(breakLoc.getY()), Math.floor(breakLoc.getZ()));
                FakeBlock fb = new FakeBlock(origin, preload.bakeKey().block(), gridN,
                        new ArrayList<>(plan.frontRefs().values()), preload.blockRotation());
                TrackedBreak tb = buildTrackedBreak(player.getUniqueId(),
                        preload.bakeKey().block(), fb, block.getBlockData(), eyeDir, plan);
                tracker.put(posKey, tb);
                tb.speculative = true;
                tb.initialGapEstimateMs = estimateInitialGapMs(block, player, cfg);
                tb.lastUpdateTickMs = System.currentTimeMillis();
                if (cfg.clientHideRealBlock()) {
                    DirectionalShrinkEffect.rescaleShell(
                            tb.fakeBlock, tb.baseScales, tb.currentScales,
                            1f / FakeBlockFactory.INITIAL_SHELL_COMPRESSION, 0);
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
                // Start one stage ahead so the wave is already at targetProgress≈0.2
                // when the FakeBlock first appears. At high gridN the tiny chunks make
                // the progress=0 state imperceptible; STAGE_PER_EVENT ensures the front
                // layer is ~80% shrunk immediately. lastAppliedProgress=STAGE_PER_EVENT
                // means the first real event (always 0.1) is skipped as a zero-delta.
                applyForward(tb, STAGE_PER_EVENT, cfg);
                return;
            }
        }

        // Normal speculative spawn.
        spawnAndRegister(player, block, breakLoc, posKey, 0d, cfg);
        TrackedBreak tb = tracker.get(posKey);
        if (tb == null) return; // gated out (not enabled / not baked / cap)
        tb.speculative = true;
        tb.initialGapEstimateMs = estimateInitialGapMs(block, player, cfg);
        tb.lastUpdateTickMs = System.currentTimeMillis();
        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] speculative-spawn at " + posKey
                        + " player=" + player.getName()
                        + " initialGapMs=" + tb.initialGapEstimateMs);
    }

    /**
     * Auto-tuned stale threshold: speculative trackers use the configured
     * left-click grace window so a no-commit swing rolls back quickly,
     * extended by the seeded first-event estimate so slow blocks (bare
     * hands on stone, anything on obsidian) aren't false-cancelled before
     * the first {@link BlockBreakProgressUpdateEvent} can legitimately
     * arrive. Otherwise BOOTSTRAP until we've seen at least one
     * inter-event gap, then 3x EMA + 200ms slack clamped to [MIN, MAX].
     */
    private static long staleThresholdFor(TrackedBreak tb, TesseraConfig cfg) {
        if (tb.speculative) {
            long grace = Math.max(100L, cfg.leftClickGraceMs());
            if (tb.initialGapEstimateMs > 0L) {
                // Slack absorbs scheduling jitter between
                // Block.getBreakSpeed and the first real progress event.
                return Math.max(grace, tb.initialGapEstimateMs + 200L);
            }
            return grace;
        }
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
        UUID id = event.getPlayer().getUniqueId();
        clearPreload(id);
        teardownPlayer(id, /*restoreBlock=*/ false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        clearPreload(id);
        teardownPlayer(id, /*restoreBlock=*/ false);
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

        // Lazy-spawn plan: front half spawned now, back half + interior pending.
        FakeBlockFactory.PreloadPlan plan;
        try {
            plan = factory.preloadAndPending(breakLoc, bakeKey, blockRotation,
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
                new ArrayList<>(plan.frontRefs().values()), blockRotation);

        TrackedBreak tb = buildTrackedBreak(player.getUniqueId(), key, fb,
                blockData, eyeDir, plan);
        tracker.put(posKey, tb);

        if (cfg.clientHideRealBlock()) {
            sendBarrierNow(tb, player, breakLoc);
        }

        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] spawn " + key + " at " + posKey
                        + " player=" + player.getName() + " progress=" + fmt(progress)
                        + " barrierSent=" + tb.barrierSent
                        + " front=" + plan.frontRefs().size()
                        + " pending=" + plan.pendingSpecs().size());

        // When progress=0 (speculative spawn on first click before any event has arrived),
        // jumpstart one stage ahead so the initial wave state is immediately perceptible
        // even at high gridN where tiny chunks make a targetProgress=0.1 state invisible.
        applyForward(tb, progress > 0d ? progress : STAGE_PER_EVENT, cfg);
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

        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] applyForward targetProgress=" + fmt(targetProgress));

        spawnPendingChunks(tb, targetProgress, cfg.waveWindow());
        boolean firstReal = tb.lastAppliedProgress <= 0d && progress > 0d;
        DirectionalShrinkEffect.applyAtProgress(tb.fakeBlock, tb.chunkT, tb.baseScales, tb.currentScales,
                targetProgress, cfg.waveWindow(), interpTicks, cfg.progressMinDelta(), cfg.collapseStyle());
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
                "[" + ts() + "] [debug-progress] force-break " + tb.key + " at " + worldBlock.getLocation()
                        + " by=" + player.getName());
        // Fires BlockBreakEvent synchronously; our BlockBreakListener.onBreak
        // sees the active tracker and disposes it via onRealBreak.
        // If breakBlock returns false the block was already gone (broken by
        // an external mechanism that didn't fire BlockBreakEvent, or cancelled
        // by another plugin). In that case onRealBreak will never run, so
        // dispose here directly to avoid entities lingering until the watchdog.
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
            } catch (RuntimeException ignored) { /* old player may have left chunk */ }
        }
        tb.currentPlayerId = newPlayer.getUniqueId();
        tb.barrierSent = false; // reset so sendBarrierNow will send to the new player
        tb.eyeDir = newPlayer.getEyeLocation().getDirection();
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
        tb.state = State.REVERSING;
        final double startProgress = tb.lastAppliedProgress;
        final long startMs = System.currentTimeMillis();
        final int durationMs = (int) Math.max(50L, Math.min(300L, Math.round(startProgress * 300d)));

        // Mirror the spawn flow: restore the real block client-side and
        // compress the lattice back inside it in the same tick. Without
        // this, partially-shrunk chunks (some at scale 0) leave see-through
        // gaps over the still-hidden BARRIER until the reverse animation
        // grows them back.
        if (tb.barrierSent) {
            Player p = Bukkit.getPlayer(tb.currentPlayerId);
            if (p != null) {
                try {
                    p.sendBlockChange(tb.origin, tb.originalBlockData);
                } catch (RuntimeException ignored) { /* player may have unloaded chunk */ }
            }
            tb.barrierSent = false;
        }
        if (tb.shellExpanded) {
            DirectionalShrinkEffect.rescaleShell(
                    tb.fakeBlock, tb.baseScales, tb.currentScales,
                    FakeBlockFactory.INITIAL_SHELL_COMPRESSION,
                    /*interpTicks=*/ 0);
            tb.shellExpanded = false;
        }

        if (cfg.debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] reverse-start " + tb.key + " at " + posKey
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
                        "[" + ts() + "] [debug-progress] reverse-done " + tb.key + " at " + posKey);
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
        // The real block is gone, but the client still has the sendBlockChange(BARRIER)
        // override. Explicitly clear it to AIR so the override is lifted in the same
        // tick as entity despawn — without this the client shows BARRIER for ~1 tick
        // after the FakeBlock disappears.
        if (tb.barrierSent) {
            Player p = Bukkit.getPlayer(tb.currentPlayerId);
            if (p != null) {
                try { p.sendBlockChange(tb.origin, Material.AIR.createBlockData()); }
                catch (RuntimeException ignored) {}
            }
            tb.barrierSent = false;
        }
        disposeImmediate(tb, /*restoreBlock=*/ false);
        // Clear any preloads aimed at this position — e.g. a second player who was
        // aiming at the block while someone else was mining it. Without this their
        // preload entities would remain visible in empty air until the next aimWatchTick.
        clearPreloadsAt(posKey);
        if (plugin.tesseraConfig().debug()) plugin.getLogger().info(
                "[" + ts() + "] [debug-progress] real-break " + tb.key + " at " + posKey);
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
                "[" + ts() + "] [debug-progress] teardown player=" + playerId + " posKey=" + posKey);
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
                    "[" + ts() + "] [debug-progress] watchdog-cancel " + tb.key + " at " + k
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

    /**
     * Send {@code sendBlockChange(BARRIER)} immediately (no deferred task).
     * The entity spawn packet and the block change packet are both sent in
     * the same tick; any brief per-frame gap before heads render is
     * imperceptible in practice. The eager-preload path avoids even that
     * by pre-spawning entities before the player clicks.
     */
    private void sendBarrierNow(TrackedBreak tb, Player player, Location loc) {
        if (tb.barrierSent) return;
        if (!tb.shellExpanded) {
            DirectionalShrinkEffect.rescaleShell(
                    tb.fakeBlock, tb.baseScales, tb.currentScales,
                    1f / FakeBlockFactory.INITIAL_SHELL_COMPRESSION, /*interpTicks=*/ 0);
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

    /**
     * Per 2-tick poll: for every survival-mode player, check the block they
     * are currently aiming at and (if it's registered and not already tracked)
     * pre-spawn a compressed FakeBlock so the entities are rendered before
     * the player starts mining.
     */
    private void aimWatchTick() {
        TesseraConfig cfg = plugin.tesseraConfig();
        if (!cfg.eagerPreload()) {
            if (!preloads.isEmpty()) {
                new ArrayList<>(preloads.keySet()).forEach(this::clearPreload);
            }
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
                if (targetKey != null && existing.posKey.equals(targetKey)) {
                    // Same block — keep preload only if it is not yet being actively mined.
                    // If another player started tracking it, our preload can never be
                    // consumed and would otherwise linger until the block breaks.
                    if (tracker.get(targetKey) == null) continue;
                    clearPreload(player.getUniqueId());
                } else {
                    clearPreload(player.getUniqueId());
                }
            }
            if (targetKey == null) continue;
            if (tracker.get(targetKey) != null) continue; // already tracking

            // Skip instamine (no animation to pre-warm).
            try {
                if (target.getBreakSpeed(player) >= 1.0f) continue;
            } catch (NoSuchMethodError | RuntimeException ignored) {}

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

            Map<ChunkCoord, ChunkRef> prespawned;
            try {
                prespawned = factory.preload(target.getLocation(), bakeKey, blockRotation, eyeDir);
            } catch (RuntimeException re) {
                continue;
            }
            if (prespawned.isEmpty()) continue;
            preloads.put(player.getUniqueId(),
                    new PreloadEntry(targetKey, bakeKey, blockRotation, prespawned));

            if (cfg.debug()) plugin.getLogger().info(
                    "[" + ts() + "] [debug-progress] eager-preload-spawn " + bakeKey
                            + " at " + targetKey + " for=" + player.getName()
                            + " chunks=" + prespawned.size());
        }
    }

    /** Despawn all pre-spawned entities for {@code playerId}, if any. */
    private void clearPreload(UUID playerId) {
        PreloadEntry entry = preloads.remove(playerId);
        if (entry != null) {
            entry.prespawnedChunks().values().forEach(ref -> {
                if (!ref.display().isDead()) ref.display().remove();
            });
        }
    }

    /**
     * Despawn preload entities for ANY player whose preload targets {@code posKey}.
     * Called when a block breaks so pre-spawned entities don't linger in empty air.
     */
    void clearPreloadsAt(BlockPosKey posKey) {
        preloads.entrySet().removeIf(e -> {
            if (!e.getValue().posKey().equals(posKey)) return false;
            e.getValue().prespawnedChunks().values().forEach(ref -> {
                if (!ref.display().isDead()) ref.display().remove();
            });
            return true;
        });
    }

    /**
     * Remove and return the preload for {@code playerId} if it matches
     * {@code posKey}. If the preload is for a different position, clears it
     * and returns null.
     */
    private PreloadEntry consumePreload(UUID playerId, BlockPosKey posKey) {
        PreloadEntry entry = preloads.remove(playerId);
        if (entry == null) return null;
        if (!entry.posKey().equals(posKey)) {
            entry.prespawnedChunks().values().forEach(ref -> {
                if (!ref.display().isDead()) ref.display().remove();
            });
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

        float[]  base    = new float[total];
        float[]  current = new float[total];
        double[] chunkT  = new double[total];

        // Front-half: read entity scale + globally-normalised t from the plan.
        for (int i = 0; i < frontCount; i++) {
            ChunkRef cr = front.get(i);
            float s = cr.display().getTransformation().getScale().x;
            base[i] = s;
            current[i] = s;
            chunkT[i] = plan.allOuterT().getOrDefault(cr.coord(), 0.0);
        }
        // Pending: t pre-populated; base/current stay 0 until the chunk is spawned.
        for (int i = 0; i < pendingCount; i++) {
            chunkT[frontCount + i] = plan.pendingSpecs().get(i).t();
        }

        TrackedBreak tb = new TrackedBreak(playerId, key, fb.origin(),
                blockData, eyeDir, fb, chunkT, base, current);
        tb.pendingChunks = new ArrayList<>(plan.pendingSpecs());
        tb.allOuterT     = new HashMap<>(plan.allOuterT());
        return tb;
    }

    /**
     * Spawn any pending chunks whose wave position is within one {@code window}
     * of {@code targetProgress}. The pending list is sorted ascending by t so we
     * stop at the first non-matching entry.
     */
    private void spawnPendingChunks(TrackedBreak tb, double targetProgress, double window) {
        if (tb.pendingChunks == null || tb.pendingChunks.isEmpty()) return;
        if (tb.fakeBlock.despawned()) return; // FakeBlock already torn down — don't spawn orphaned entities
        float shellFactor = tb.shellExpanded ? 1.0f : FakeBlockFactory.INITIAL_SHELL_COMPRESSION;
        float chunkScale  = 2f / tb.fakeBlock.gridN();

        Iterator<FakeBlockFactory.PendingChunkSpec> it = tb.pendingChunks.iterator();
        while (it.hasNext()) {
            FakeBlockFactory.PendingChunkSpec spec = it.next();
            if (spec.t() > targetProgress + window) break; // sorted — stop here
            it.remove();

            ChunkRef ref;
            try {
                ref = factory.spawnPendingChunk(tb.fakeBlock.origin(), spec,
                        tb.fakeBlock.blockRotation(), shellFactor);
            } catch (RuntimeException re) {
                plugin.getLogger().warning(
                        "spawnPendingChunk failed for " + spec.coord() + ": " + re.getMessage());
                continue;
            }

            int idx = tb.fakeBlock.chunks().size();
            tb.fakeBlock.chunks().add(ref);

            if (spec.interior()) {
                // Interior tent: baseScale = full chunk scale (tent peak), currentScale = -1f
                // sentinel so applyAtProgress writes on the first tick rather than skipping
                // (Math.abs(target - (-1)) >= minDelta is always true).
                tb.baseScales[idx]    = chunkScale;
                tb.currentScales[idx] = -1f;
            } else {
                float baseAtSpawn = chunkScale * shellFactor;
                // Compute the wave-correct scale for this chunk's wave position at the
                // current progress, so the entity spawns at its proper size rather than
                // at full scale. Without this, entities "pop" to full scale for one tick
                // before applyAtProgress animates them down.
                double sFraction = ChunkWaveSampler.shrunkFraction(spec.t(), targetProgress, window);
                float correctScale = Math.max(0f, (float)(baseAtSpawn * (1.0 - sFraction)));
                tb.baseScales[idx]    = baseAtSpawn;
                tb.currentScales[idx] = correctScale;
                if (correctScale < baseAtSpawn) {
                    Transformation cur = ref.display().getTransformation();
                    ref.display().setInterpolationDelay(0);
                    ref.display().setInterpolationDuration(0);
                    ref.display().setTransformation(new org.bukkit.util.Transformation(
                            cur.getTranslation(), cur.getLeftRotation(),
                            new org.joml.Vector3f(correctScale, correctScale, correctScale),
                            cur.getRightRotation()));
                }
            }
            // chunkT[idx] was pre-populated in buildTrackedBreak.
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

        int aliveCount   = alive.size();
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

        // Update tb.chunkT for alive chunks.
        for (int i = 0; i < aliveCount; i++) {
            tb.chunkT[i] = (proj[i] - minP) / range;
        }
        // Rebuild pending list with updated t values, re-sort, realign chunkT slots.
        if (!pending.isEmpty()) {
            List<FakeBlockFactory.PendingChunkSpec> rebuilt = new ArrayList<>(pendingCount);
            for (int i = 0; i < pendingCount; i++) {
                FakeBlockFactory.PendingChunkSpec old = pending.get(i);
                rebuilt.add(new FakeBlockFactory.PendingChunkSpec(
                        old.coord(), old.registryEntry(),
                        (proj[aliveCount + i] - minP) / range,
                        old.interior()));
            }
            rebuilt.sort((a, b) -> Double.compare(a.t(), b.t()));
            tb.pendingChunks = rebuilt;
            for (int i = 0; i < pendingCount; i++) {
                tb.chunkT[aliveCount + i] = rebuilt.get(i).t();
            }
        }
    }

    private static String fmt(double d) { return String.format("%.3f", d); }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static String ts() { return LocalTime.now().format(TS_FMT); }
}
