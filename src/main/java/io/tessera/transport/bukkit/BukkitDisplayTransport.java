package io.tessera.transport.bukkit;

import io.tessera.transport.DisplayTransport;
import io.tessera.transport.TransportSession;
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
