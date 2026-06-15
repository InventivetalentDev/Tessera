package org.inventivetalent.tessera.api;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

/**
 * Convenience accessor for the {@link TesseraApi} service.
 *
 * <p>Sugar over {@code Bukkit.getServicesManager().load(TesseraApi.class)}.
 * Returns {@code null} if Tessera isn't installed/enabled, so guard your calls
 * or declare {@code depend: [Tessera]} to be sure it's present.
 */
public final class Tessera {

    private Tessera() {}

    /** @return the live API, or {@code null} if Tessera isn't available. */
    public static @Nullable TesseraApi api() {
        return Bukkit.getServicesManager().load(TesseraApi.class);
    }

    /** @return true if the Tessera API service is currently registered. */
    public static boolean isAvailable() {
        return api() != null;
    }
}
