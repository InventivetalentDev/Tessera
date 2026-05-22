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

    /**
     * Run {@code body} with all packet sends on this session captured into one or more
     * client bundle packets (so the client applies them atomically). The default
     * implementation just runs the body — only the packet transport actually bundles.
     *
     * <p>Bundle size is capped per-session (config) to keep the client from stalling
     * on a huge atomic batch. Spawn handles returned mid-body remain valid; the
     * underlying entity-creation packets are flushed when the body exits.
     */
    default void runBundled(Runnable body) {
        body.run();
    }
}
