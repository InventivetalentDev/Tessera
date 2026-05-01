package io.tessera.transport.packet;

import io.tessera.transport.DisplayHandle;
import io.tessera.transport.TransportSession;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PacketTransportSession implements TransportSession {

    private final Player viewer;
    private final List<PacketDisplayHandle> handles = new ArrayList<>();

    PacketTransportSession(Player viewer) {
        this.viewer = viewer;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public DisplayHandle spawn(Location origin, ItemStack item, Transformation initial, float viewRange) {
        int entityId = DisplayDataAccessors.nextEntityId();

        var addPacket = new ClientboundAddEntityPacket(
                entityId, UUID.randomUUID(),
                origin.getX(), origin.getY(), origin.getZ(),
                0f, 0f,
                EntityType.ITEM_DISPLAY, 0,
                Vec3.ZERO, 0.0
        );

        net.minecraft.world.item.ItemStack nmsItem = CraftItemStack.asNMSCopy(item);
        var dataPacket = new ClientboundSetEntityDataPacket(entityId, List.of(
                SynchedEntityData.DataValue.create(DisplayDataAccessors.INTERP_START_DELTA_TICKS, 0),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.INTERP_DURATION, 0),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.TRANSLATION, initial.getTranslation()),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.SCALE, initial.getScale()),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.LEFT_ROTATION, initial.getLeftRotation()),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.RIGHT_ROTATION, initial.getRightRotation()),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.VIEW_RANGE, viewRange),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.ITEM_STACK, nmsItem),
                SynchedEntityData.DataValue.create(DisplayDataAccessors.ITEM_DISPLAY, (byte) 0)  // NONE
        ));

        send(addPacket);
        send(dataPacket);

        PacketDisplayHandle handle = new PacketDisplayHandle(entityId, viewer, initial);
        handles.add(handle);
        return handle;
    }

    @Override
    public void close() {
        if (handles.isEmpty()) return;
        if (!viewer.isOnline()) { handles.clear(); return; }
        int[] ids = new int[handles.size()];
        for (int i = 0; i < handles.size(); i++) ids[i] = handles.get(i).entityId();
        send(new ClientboundRemoveEntitiesPacket(ids));
        handles.clear();
    }

    private void send(net.minecraft.network.protocol.Packet<?> packet) {
        ((CraftPlayer) viewer).getHandle().connection.send(packet);
    }
}
