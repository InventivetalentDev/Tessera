package org.inventivetalent.tessera.transport.bukkit;

import org.inventivetalent.tessera.transport.DisplayTransport;
import org.inventivetalent.tessera.transport.TransportSession;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class BukkitDisplayTransport implements DisplayTransport {

    private final Plugin plugin;

    public BukkitDisplayTransport(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public TransportSession openSession(Player viewer, World world) {
        return new BukkitTransportSession(viewer, plugin);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
