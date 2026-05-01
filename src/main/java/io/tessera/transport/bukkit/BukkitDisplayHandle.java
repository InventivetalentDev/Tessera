package io.tessera.transport.bukkit;

import io.tessera.transport.DisplayHandle;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

final class BukkitDisplayHandle implements DisplayHandle {

    private final ItemDisplay display;
    private Transformation lastTx;

    BukkitDisplayHandle(ItemDisplay display, Transformation initial) {
        this.display = display;
        this.lastTx = initial;
    }

    @Override
    public boolean isAlive() {
        return !display.isDead();
    }

    @Override
    public Transformation getTransformation() {
        return lastTx;
    }

    @Override
    public void setTransformation(Transformation tx, int delayTicks, int durationTicks) {
        this.lastTx = tx;
        display.setInterpolationDelay(delayTicks);
        display.setInterpolationDuration(durationTicks);
        display.setTransformation(tx);
    }

    @Override
    public void setItemStack(ItemStack item) {
        display.setItemStack(item);
    }

    @Override
    public void despawn() {
        if (!display.isDead()) display.remove();
    }
}
