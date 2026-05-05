package org.inventivetalent.tessera.transport;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

/** Owns all chunk handles for one FakeBlock lifetime. Closed when the FakeBlock despawns. */
public interface TransportSession {
    /** Spawn one chunk display at {@code origin} and return its handle. */
    DisplayHandle spawn(Location origin, ItemStack item, Transformation initial, float viewRange);

    /** Despawn every handle created by this session. Idempotent. */
    void close();
}
