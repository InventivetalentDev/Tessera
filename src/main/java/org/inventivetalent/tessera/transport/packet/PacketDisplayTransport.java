package org.inventivetalent.tessera.transport.packet;

import org.inventivetalent.tessera.transport.DisplayTransport;
import org.inventivetalent.tessera.transport.TransportSession;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class PacketDisplayTransport implements DisplayTransport {

    @Override
    public TransportSession openSession(Player viewer, World world) {
        return new PacketTransportSession(viewer);
    }

    @Override
    public boolean isAvailable() {
        return DisplayDataAccessors.available;
    }
}
