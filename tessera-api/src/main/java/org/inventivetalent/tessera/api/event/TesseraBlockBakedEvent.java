package org.inventivetalent.tessera.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.inventivetalent.tessera.core.BlockKey;
import org.jetbrains.annotations.NotNull;

/**
 * Fired on the main thread when a block becomes newly available through a
 * runtime bake — either a player breaking an un-baked block or a
 * {@link org.inventivetalent.tessera.api.TesseraApi#requestBake} call that
 * succeeded. Lets dependent plugins react (e.g. unlock the block in a palette)
 * instead of polling {@link org.inventivetalent.tessera.api.TesseraApi#isAvailable}.
 *
 * <p>Not fired for blocks already present at startup (bundled/addon/cache).
 */
public class TesseraBlockBakedEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BlockKey block;
    private final int gridN;

    public TesseraBlockBakedEvent(@NotNull BlockKey block, int gridN) {
        this.block = block;
        this.gridN = gridN;
    }

    /** @return the block that was just baked. */
    public @NotNull BlockKey block() {
        return block;
    }

    /** @return the grid density the block was baked at. */
    public int gridN() {
        return gridN;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
