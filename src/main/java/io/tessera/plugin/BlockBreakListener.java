package io.tessera.plugin;

import io.tessera.assemble.FakeBlockFactory;
import io.tessera.core.BlockKey;
import io.tessera.core.FakeBlock;
import io.tessera.effect.EffectContext;
import io.tessera.effect.builtin.DirectionalShrinkEffect;
import io.tessera.skin.HeadsRegistry;
import io.tessera.skin.bake.BlockBaker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class BlockBreakListener implements Listener {

    private final TesseraPlugin plugin;
    private final FakeBlockFactory factory;
    private final HeadsRegistry registry;
    private final BlockBaker baker;
    private final DirectionalShrinkEffect effect = new DirectionalShrinkEffect();
    private final AtomicInteger active = new AtomicInteger();

    public BlockBreakListener(TesseraPlugin plugin, FakeBlockFactory factory,
                              HeadsRegistry registry, BlockBaker baker) {
        this.plugin = plugin;
        this.factory = factory;
        this.registry = registry;
        this.baker = baker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        TesseraConfig cfg = plugin.tesseraConfig();
        Material mat = event.getBlock().getType();
        BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());

        if (!cfg.enables(key.asString())) {
            if (cfg.debug()) plugin.getLogger().info("[debug] skip " + key + " (not enabled)");
            return;
        }
        if (active.get() >= cfg.maxConcurrentFakeBlocks()) {
            if (cfg.debug()) plugin.getLogger().info("[debug] skip " + key + " (concurrency cap)");
            return;
        }

        Location breakLoc = event.getBlock().getLocation();
        Vector eyeDir = event.getPlayer().getEyeLocation().getDirection();
        UUID world = event.getPlayer().getWorld().getUID();

        if (registry.has(key)) {
            spawn(key, breakLoc, eyeDir, cfg);
            return;
        }

        // Not baked yet — kick off async runtime bake. The block is already
        // gone (vanilla has run); when the bake completes (a few seconds
        // typically) we spawn the FakeBlock at the now-air cell. The
        // post-hoc effect is unusual but acceptable for v1.
        if (cfg.debug()) plugin.getLogger().info("[debug] runtime-baking " + key);
        baker.bake(key).whenComplete((ok, ex) -> {
            if (ex != null) {
                plugin.getLogger().warning("[runtime-bake] " + key + " threw: " + ex.getMessage());
                return;
            }
            if (!ok) return;
            // Re-check world is still loaded; player may have logged off.
            if (Bukkit.getWorld(world) == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> spawn(key, breakLoc, eyeDir, cfg));
        });
    }

    private void spawn(BlockKey key, Location breakLoc, Vector eyeDir, TesseraConfig cfg) {
        if (active.get() >= cfg.maxConcurrentFakeBlocks()) return;
        active.incrementAndGet();
        FakeBlock fb;
        try {
            fb = factory.create(breakLoc, key);
        } catch (RuntimeException re) {
            active.decrementAndGet();
            plugin.getLogger().warning("Failed to spawn FakeBlock for " + key + ": " + re.getMessage());
            return;
        }

        EffectContext ctx = new EffectContext(eyeDir, System.currentTimeMillis(), cfg.effectDurationMs(), plugin);
        effect.apply(fb, ctx);

        long despawnTicks = (cfg.effectDurationMs() / 50L) + 5L;
        plugin.getServer().getScheduler().runTaskLater(plugin, active::decrementAndGet, despawnTicks);
    }
}
