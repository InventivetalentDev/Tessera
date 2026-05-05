package org.inventivetalent.tessera.assemble;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.inventivetalent.tessera.skin.HeadSkin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Builds and caches {@link Material#PLAYER_HEAD} ItemStacks carrying the
 * MineSkin texture profile of a {@link HeadSkin}. Mosaikin port — same
 * mechanics, cache keyed by {@link HeadSkin#id()}.
 *
 * <p>The {@link PlayerProfile} uses a fresh random UUID and the literal name
 * {@code "tessera"}: vanilla Mojang-account collisions don't matter because
 * we never look the head up by name, only by texture. Caching avoids
 * rebuilding the profile + ItemMeta on every spawn — a meaningful saving
 * when one block break spawns 56 chunks all sharing the same uniform-block
 * head.
 */
public final class HeadItemFactory {

    private final ConcurrentMap<UUID, ItemStack> cache = new ConcurrentHashMap<>();

    public ItemStack build(HeadSkin head) {
        if (head == null || head.textureValue() == null || head.textureSignature() == null) {
            return new ItemStack(Material.PLAYER_HEAD);
        }
        return cache.computeIfAbsent(head.id(), id -> create(head)).clone();
    }

    public void invalidate(HeadSkin head) {
        cache.remove(head.id());
    }

    public void clear() {
        cache.clear();
    }

    private ItemStack create(HeadSkin head) {
        PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), "tessera");
        profile.setProperty(new ProfileProperty(
                "textures", head.textureValue(), head.textureSignature()));

        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            meta.setPlayerProfile(profile);
            item.setItemMeta(meta);
        }
        return item;
    }
}
