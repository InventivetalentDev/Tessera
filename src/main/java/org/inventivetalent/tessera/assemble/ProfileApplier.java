package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.skin.HeadSkin;
import org.inventivetalent.tessera.util.PlatformDetector;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.logging.Logger;

/**
 * Writes a {@link HeadSkin}'s MineSkin texture profile onto a
 * {@link SkullMeta}. Two backing implementations, both signature-preserving:
 *
 * <ul>
 *   <li>{@link PaperProfileApplier} — uses Paper's {@code PlayerProfile} +
 *       {@code ProfileProperty} API. Clean, no reflection.</li>
 *   <li>{@link NmsProfileApplier} — reflects on Mojang's authlib
 *       ({@code com.mojang.authlib.GameProfile}, bundled by every
 *       vanilla-derived server) and writes the profile onto
 *       {@code CraftMetaSkull.profile} directly. Works on Spigot.</li>
 * </ul>
 *
 * <p>Both paths preserve the MineSkin {@code value} + {@code signature}
 * byte-for-byte — anything less and player-head items render blank or with
 * the wrong texture in some clients.
 *
 * <p>{@link #create(Logger)} picks at startup. The Paper impl is loaded by
 * reflection so the JVM never tries to link its
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
                        + "). Falling back to NMS reflection.");
            }
        }
        return new NmsProfileApplier(logger);
    }
}
