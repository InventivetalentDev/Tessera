package org.inventivetalent.tessera.transport.packet;

import org.inventivetalent.tessera.transport.DisplayTransport;
import org.inventivetalent.tessera.transport.TransportSession;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.function.IntSupplier;

public final class PacketDisplayTransport implements DisplayTransport {

    private final IntSupplier batchSizeSupplier;

    /**
     * @param batchSizeSupplier reads the live config value so {@code /tessera reload} picks up
     *                          changes without restarting; {@code 0} disables bundling.
     */
    public PacketDisplayTransport(IntSupplier batchSizeSupplier) {
        this.batchSizeSupplier = batchSizeSupplier;
    }

    @Override
    public TransportSession openSession(Player viewer, World world) {
        return new PacketTransportSession(viewer, batchSizeSupplier);
    }

    @Override
    public boolean isAvailable() {
        return DisplayDataAccessors.available;
    }
}
