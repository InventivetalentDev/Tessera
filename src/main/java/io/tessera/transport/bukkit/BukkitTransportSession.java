package io.tessera.transport.bukkit;

import io.tessera.transport.DisplayHandle;
import io.tessera.transport.TransportSession;
import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.List;

final class BukkitTransportSession implements TransportSession {

    private final Player viewer;
    private final Plugin plugin;
    private final List<BukkitDisplayHandle> handles = new ArrayList<>();

    BukkitTransportSession(Player viewer, Plugin plugin) {
        this.viewer = viewer;
        this.plugin = plugin;
    }

    @Override
    public DisplayHandle spawn(Location origin, ItemStack item, Transformation initial, float viewRange) {
        ItemDisplay display = origin.getWorld().spawn(origin, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(initial);
            d.setInterpolationDuration(0);
            d.setInterpolationDelay(0);
            d.setViewRange(viewRange);
            d.setPersistent(false);
            d.setVisibleByDefault(false);
        });
        viewer.showEntity(plugin, display);
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
