package io.tessera.plugin;

import io.tessera.assemble.FakeBlockFactory;
import io.tessera.core.BlockKey;
import io.tessera.core.FakeBlock;
import io.tessera.effect.EffectContext;
import io.tessera.effect.builtin.DirectionalShrinkEffect;
import io.tessera.skin.HeadsRegistry;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.concurrent.atomic.AtomicInteger;

public final class BlockBreakListener implements Listener {

    private final TesseraPlugin plugin;
    private final FakeBlockFactory factory;
    private final HeadsRegistry registry;
    private final DirectionalShrinkEffect effect = new DirectionalShrinkEffect();
    private final AtomicInteger active = new AtomicInteger();

    public BlockBreakListener(TesseraPlugin plugin, FakeBlockFactory factory, HeadsRegistry registry) {
        this.plugin = plugin;
        this.factory = factory;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        TesseraConfig cfg = plugin.tesseraConfig();
        Material mat = event.getBlock().getType();
        BlockKey key = BlockKey.of(mat.getKey().getNamespace() + ":" + mat.getKey().getKey());

        if (!cfg.enables(key.asString())) return;
        if (!registry.has(key)) return;
        if (active.get() >= cfg.maxConcurrentFakeBlocks()) return;

        active.incrementAndGet();
        FakeBlock fb;
        try {
            fb = factory.create(event.getBlock().getLocation(), key);
        } catch (RuntimeException re) {
            active.decrementAndGet();
            plugin.getLogger().warning("Failed to spawn FakeBlock for " + key + ": " + re.getMessage());
            return;
        }

        EffectContext ctx = new EffectContext(
                event.getPlayer().getEyeLocation().getDirection(),
                System.currentTimeMillis(),
                cfg.effectDurationMs(),
                plugin);
        effect.apply(fb, ctx);

        // Decrement after the cleanup window — slightly conservative but
        // avoids relying on the effect's own callback path.
        long despawnTicks = (cfg.effectDurationMs() / 50L) + 5L;
        plugin.getServer().getScheduler().runTaskLater(plugin, active::decrementAndGet, despawnTicks);
    }
}
