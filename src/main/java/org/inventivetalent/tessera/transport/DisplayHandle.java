package org.inventivetalent.tessera.transport;

import org.bukkit.util.Transformation;

/** Per-chunk handle; abstracts whether the chunk is a real Bukkit entity or a packet-only phantom. */
public interface DisplayHandle {
    boolean isAlive();

    /** Returns the last transformation that was set on this handle. */
    Transformation getTransformation();

    /** Set transformation with the given interpolation timing (delayTicks = 0 means start immediately). */
    void setTransformation(Transformation tx, int delayTicks, int durationTicks);

    void despawn();
}
