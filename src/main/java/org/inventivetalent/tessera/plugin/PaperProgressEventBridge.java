package org.inventivetalent.tessera.plugin;

import io.papermc.paper.event.block.BlockBreakProgressUpdateEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Paper-only bridge: subscribes to {@link BlockBreakProgressUpdateEvent}
 * and delegates to {@link BlockBreakProgressListener#handleProgress}. Kept
 * as a separate class so the JVM only links the Paper event type when this
 * class is loaded — and it's loaded by reflection in {@code TesseraPlugin}
 * only when {@code PlatformDetector.PAPER} is true. On Spigot the class is
 * never touched and the Paper event symbol never resolves.
 */
public final class PaperProgressEventBridge implements Listener, ProgressSource {

    private final TesseraPlugin plugin;
    private final BlockBreakProgressListener delegate;

    public PaperProgressEventBridge(TesseraPlugin plugin, BlockBreakProgressListener delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    @Override
    public void register() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Progress source: Paper BlockBreakProgressUpdateEvent");
    }

    @Override
    public void shutdown() {
        // Bukkit unregisters our Listener when the plugin disables; nothing to do.
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProgress(BlockBreakProgressUpdateEvent event) {
        Entity src = event.getEntity();
        if (!(src instanceof Player player)) return;
        delegate.handleProgress(player, event.getBlock(), event.getProgress());
    }
}
