package org.inventivetalent.tessera.assemble;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.inventivetalent.tessera.skin.HeadSkin;
import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;

/**
 * Paper-only implementation. Loaded by {@link ProfileApplier#create} via
 * reflection only when Paper is detected, so its
 * {@code com.destroystokyo.paper.profile.*} imports never link on Spigot.
 */
final class PaperProfileApplier implements ProfileApplier {

    @Override
    public boolean apply(SkullMeta meta, HeadSkin head) {
        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "tessera");
        profile.setProperty(new ProfileProperty(
                "textures", head.textureValue(), head.textureSignature()));
        meta.setPlayerProfile(profile);
        return true;
    }
}
