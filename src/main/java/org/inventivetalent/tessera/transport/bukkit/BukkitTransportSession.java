package org.inventivetalent.tessera.transport.bukkit;

import org.inventivetalent.tessera.transport.DisplayHandle;
import org.inventivetalent.tessera.transport.TransportSession;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class BukkitTransportSession implements TransportSession {

    /**
     * {@code Player.showEntity(Plugin, Entity)} is Paper-only. Resolved
     * reflectively at class-load: present on Paper, null on Spigot. When
     * null, displays spawned with {@code setVisibleByDefault(false)}
     * remain hidden from everyone, so the Spigot fallback path drops
     * per-viewer hiding and lets the display be world-visible. The
     * PacketEvents transport (chunk 2) provides true per-viewer
     * targeting on both platforms — Bukkit transport is only the
     * fallback when Paper is detected and packet transport isn't
     * explicitly chosen.
     */
    private static final Method SHOW_ENTITY = resolveShowEntity();

    private static Method resolveShowEntity() {
        try {
            return Player.class.getMethod("showEntity", Plugin.class, Entity.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private final Player viewer;
    private final Plugin plugin;
    private final List<BukkitDisplayHandle> handles = new ArrayList<>();

    BukkitTransportSession(Player viewer, Plugin plugin) {
        this.viewer = viewer;
        this.plugin = plugin;
    }

    @Override
    public DisplayHandle spawn(Location origin, ItemStack item, Transformation initial, float viewRange) {
        boolean perViewer = SHOW_ENTITY != null;
        ItemDisplay display = origin.getWorld().spawn(origin, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(initial);
            d.setInterpolationDuration(0);
            d.setInterpolationDelay(0);
            d.setViewRange(viewRange);
            d.setPersistent(false);
            // Only hide-by-default when we have a way to selectively re-show:
            // on Spigot without showEntity, the display would stay invisible
            // for everyone, which defeats the purpose.
            if (perViewer) d.setVisibleByDefault(false);
        });
        if (perViewer) {
            try {
                SHOW_ENTITY.invoke(viewer, plugin, display);
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                // showEntity resolved but invocation failed — display is hidden
                // and we can't unhide it. Cleanly remove rather than orphan.
                display.remove();
                throw new IllegalStateException("showEntity invocation failed");
            }
        }
        BukkitDisplayHandle handle = new BukkitDisplayHandle(display, initial);
        handles.add(handle);
        return handle;
    }

    @Override
    public void close() {
        for (BukkitDisplayHandle h : handles) h.despawn();
        handles.clear();
    }
}
