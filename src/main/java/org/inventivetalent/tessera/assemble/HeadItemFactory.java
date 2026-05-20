package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.skin.HeadSkin;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * Builds and caches {@link Material#PLAYER_HEAD} ItemStacks carrying the
 * MineSkin texture profile of a {@link HeadSkin}. Mosaikin port — same
 * mechanics, cache keyed by {@link HeadSkin#id()}.
 *
 * <p>Profile application is delegated to a {@link ProfileApplier} picked at
 * startup: Paper-API on Paper, standard Bukkit-API on Spigot. The Paper
 * path preserves MineSkin signatures; the Bukkit path drops them, which is
 * fine for player-head item rendering.
 */
public final class HeadItemFactory {

    private final ConcurrentMap<UUID, ItemStack> cache = new ConcurrentHashMap<>();
    private final ProfileApplier applier;

    public HeadItemFactory(Logger logger) {
        this.applier = ProfileApplier.create(logger);
    }

    public ItemStack build(HeadSkin head) {
        if (head == null || head.textureValue() == null || head.textureSignature() == null) {
            return new ItemStack(Material.PLAYER_HEAD);
        }
        return cache.computeIfAbsent(head.id(), id -> create(head)).clone();
    }

    public void clear() {
        cache.clear();
    }

    private ItemStack create(HeadSkin head) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            applier.apply(meta, head);
            item.setItemMeta(meta);
        }
        return item;
    }
}
