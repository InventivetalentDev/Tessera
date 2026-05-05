package org.inventivetalent.tessera.transport;

import org.bukkit.World;
import org.bukkit.entity.Player;

/** Factory for per-FakeBlock transport sessions. Implementations: Bukkit API or raw NMS packets. */
public interface DisplayTransport {
    /** Open a session that will send all display updates to {@code viewer} only. */
    TransportSession openSession(Player viewer, World world);

    /**
     * Returns false if this transport is unavailable (e.g. reflection init failed after an MC
     * update). The plugin falls back to the Bukkit transport when this returns false.
     */
    boolean isAvailable();
}
