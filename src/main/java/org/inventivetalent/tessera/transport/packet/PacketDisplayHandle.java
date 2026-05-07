package org.inventivetalent.tessera.transport.packet;

import org.inventivetalent.tessera.transport.DisplayHandle;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class PacketDisplayHandle implements DisplayHandle {

    private final int entityId;
    private final Player viewer;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private Transformation lastTx;

    PacketDisplayHandle(int entityId, Player viewer, Transformation initial) {
        this.entityId = entityId;
        this.viewer = viewer;
        this.lastTx = initial;
    }

    int entityId() {
        return entityId;
    }

    @Override
    public boolean isAlive() {
        return alive.get() && viewer.isOnline();
    }

    @Override
    public Transformation getTransformation() {
        return lastTx;
    }

    @Override
    public void setTransformation(Transformation tx, int delayTicks, int durationTicks) {
        this.lastTx = tx;
        if (!isAlive()) return;
        // start_delta_ticks must precede transform fields so the client begins
        // lerping from the correct tick rather than snapping.
        var packet = new ClientboundSetEntityDataPacket(entityId, List.of(
                SynchedEntityData.DataValue.create(DisplayDataAccessors.INTERP_START_DELTA_TICKS, delayTicks),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.INTERP_DURATION, durationTicks),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.TRANSLATION, tx.getTranslation()),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.SCALE, tx.getScale()),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.LEFT_ROTATION, tx.getLeftRotation()),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.RIGHT_ROTATION, tx.getRightRotation())
        ));
        send(packet);
    }

    @Override
    public void despawn() {
        if (!alive.compareAndSet(true, false)) return;
        if (!viewer.isOnline()) return;
        send(new ClientboundRemoveEntitiesPacket(entityId));
    }

    private void send(net.minecraft.network.protocol.Packet<?> packet) {
        ((CraftPlayer) viewer).getHandle().connection.send(packet);
    }
}
