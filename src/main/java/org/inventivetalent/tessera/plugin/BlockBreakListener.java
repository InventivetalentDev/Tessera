package org.inventivetalent.tessera.plugin;

import org.inventivetalent.tessera.assemble.FakeBlockFactory;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.FakeBlock;
import org.inventivetalent.tessera.core.VariantKey;
import org.inventivetalent.tessera.effect.EffectContext;
import org.inventivetalent.tessera.effect.builtin.DirectionalShrinkEffect;
import org.inventivetalent.tessera.nms.BlockTintReader;
import org.bukkit.entity.Player;
import org.inventivetalent.tessera.plugin.ProgressTracker.BlockPosKey;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.inventivetalent.tessera.skin.bake.BlockBaker;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;

import java.util.concurrent.atomic.AtomicInteger;

public final class BlockBreakListener implements Listener {

    private final TesseraPlugin plugin;
    private final FakeBlockFactory factory;
    private final HeadsRegistry registry;
    private final BlockBaker baker;
    private final AtomicInteger active;
    private final BlockBreakProgressListener progressListener;

    public BlockBreakListener(TesseraPlugin plugin, FakeBlockFactory factory,
                              HeadsRegistry registry, BlockBaker baker,
                              AtomicInteger active,
                              BlockBreakProgressListener progressListener) {
        this.plugin = plugin;
        this.factory = factory;
        this.registry = registry;
        this.baker = baker;
        this.active = active;
        this.progressListener = progressListener;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        TesseraConfig cfg = plugin.tesseraConfig();
        Material mat = event.getBlock().getType();
        BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());
        Location breakLoc = event.getBlock().getLocation();
        BlockPosKey posKey = BlockPosKey.of(breakLoc);

        // Progress-driven path already spawned & animated the FakeBlock;
        // vanilla just removed the real block. Tear down the tracker and
        // skip the post-break wave entirely. onRealBreak also sweeps any
        // preloads aimed at this position.
        if (progressListener.isTracked(posKey)) {
            progressListener.onRealBreak(posKey);
            return;
        }

        // No active tracker — but someone might have an eager preload aimed here
        // (e.g. block broken by instamine, explosion, or another plugin).
        progressListener.clearPreloadsAt(posKey);

        // Mirror the progress listener's fast-break gate: if the block would have
        // been skipped there (estimated duration < minBreakDurationMs), don't spawn
        // a post-break animation either — it would play on already-empty air.
        int minDuration = cfg.minBreakDurationMs();
        if (minDuration > 0
                && BlockBreakProgressListener.estimateBreakDurationMs(event.getBlock(), event.getPlayer())
                        < minDuration) {
            return;
        }

        if (!cfg.enables(key.asString())) {
            if (cfg.debug()) plugin.getLogger().info("[debug] skip " + key + " (not enabled)");
            return;
        }
        if (active.get() >= cfg.maxConcurrentFakeBlocks()) {
            if (cfg.debug()) plugin.getLogger().info("[debug] skip " + key + " (concurrency cap)");
            return;
        }

        // BlockData captures the full state (axis, facing, lit, ...) — needed
        // so non-default variants render with the correct rotation.
        BlockData blockData = event.getBlock().getBlockData();
        Vector eyeDir = event.getPlayer().getEyeLocation().getDirection();

        // Read biome tint synchronously on the main thread before any async
        // hop — getBlockTint touches chunk biome storage which races with
        // chunk unloads off-thread. Untinted blocks get tint=0 (sentinel),
        // matching the historical untinted code path.
        int tint = cfg.enableTintedBlocks() ? BlockTintReader.read(event.getBlock()) : 0;
        BakeKey bakeKey = new BakeKey(key, tint);

        Player player = event.getPlayer();
        if (registry.has(bakeKey)) {
            spawn(player, bakeKey, blockData, breakLoc, eyeDir, cfg);
            return;
        }

        // Not baked yet — fire the runtime bake for next time (so a player
        // breaking the same block again gets the effect) but don't spawn
        // anything for *this* break. The post-hoc effect at an already-empty
        // cell looked off, and silently dropping is preferable to a delayed
        // surprise. BlockBaker.inflight dedupes simultaneous calls for the
        // same key, so spam-breaking the same block won't pile up MineSkin
        // requests; partial-failure bakes leave the registry empty so the
        // next bake() retries only the missing chunks via SkinDiskCache.
        if (cfg.debug()) plugin.getLogger().info("[debug] runtime-baking " + bakeKey);
        baker.bake(bakeKey);
    }

    private void spawn(Player viewer, BakeKey bakeKey, BlockData blockData,
                       Location breakLoc, Vector eyeDir, TesseraConfig cfg) {
        if (active.get() >= cfg.maxConcurrentFakeBlocks()) return;
        active.incrementAndGet();
        BlockKey key = bakeKey.block();
        String fullStateKey = VariantKey.fromBlockData(blockData);
        String matchedKey = VariantKey.pickMatching(fullStateKey, registry.variantsFor(key).keySet());
        Quaternionf blockRotation = registry.rotationFor(key, matchedKey);

        FakeBlock fb;
        try {
            fb = factory.create(viewer, breakLoc, bakeKey, blockRotation, cfg.fillInterior(), eyeDir);
        } catch (RuntimeException re) {
            active.decrementAndGet();
            plugin.getLogger().warning("Failed to spawn FakeBlock for " + bakeKey + ": " + re.getMessage());
            return;
        }

        EffectContext ctx = new EffectContext(eyeDir, System.currentTimeMillis(), cfg.effectDurationMs(), plugin);
        new DirectionalShrinkEffect(cfg.collapseStyle()).applyTimed(fb, ctx);

        long despawnTicks = (cfg.effectDurationMs() / 50L) + 5L;
        plugin.getServer().getScheduler().runTaskLater(plugin, active::decrementAndGet, despawnTicks);
    }
}
