package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.skin.HeadSkin;
import org.inventivetalent.tessera.util.PlatformDetector;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.logging.Logger;

/**
 * Writes a {@link HeadSkin}'s MineSkin texture profile onto a
 * {@link SkullMeta}. Two backing implementations:
 *
 * <ul>
 *   <li>{@link PaperProfileApplier} — uses Paper's {@code PlayerProfile} +
 *       {@code ProfileProperty} API, preserving both the texture value and
 *       its MineSkin signature byte-for-byte.</li>
 *   <li>{@link BukkitProfileApplier} — uses the standard Bukkit
 *       {@code PlayerProfile} / {@code PlayerTextures.setSkin(URL)} path.
 *       Works on Spigot but discards the MineSkin signature (clients still
 *       render the skin fine since the texture-URL property doesn't require
 *       a signature for player-head items).</li>
 * </ul>
 *
 * <p>{@link #create(Logger)} picks the right backend at startup. The Paper
 * impl is loaded by reflection so the JVM never tries to link its
 * {@code com.destroystokyo.paper.profile.*} imports on Spigot.
 */
public interface ProfileApplier {

    /** Apply the head's texture to the meta. Returns true on success. */
    boolean apply(SkullMeta meta, HeadSkin head);

    static ProfileApplier create(Logger logger) {
        if (PlatformDetector.PAPER) {
            try {
                Class<?> cls = Class.forName(
                        "org.inventivetalent.tessera.assemble.PaperProfileApplier");
                return (ProfileApplier) cls.getDeclaredConstructor().newInstance();
            } catch (Throwable t) {
                logger.warning("[profile] Paper detected but PaperProfileApplier failed to load ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + "). Falling back to Bukkit profile API; MineSkin signatures will be dropped.");
            }
        }
        return new BukkitProfileApplier(logger);
    }
}
