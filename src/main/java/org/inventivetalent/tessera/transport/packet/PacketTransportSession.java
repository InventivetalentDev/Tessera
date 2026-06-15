package org.inventivetalent.tessera.transport.packet;

import org.inventivetalent.tessera.transport.DisplayHandle;
import org.inventivetalent.tessera.transport.TransportSession;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
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
import java.util.function.IntSupplier;

final class PacketTransportSession implements TransportSession {

    /** Vanilla client cap on sub-packets per ClientboundBundlePacket. */
    private static final int VANILLA_BUNDLE_CAP = 4096;

    private final Player viewer;
    private final IntSupplier batchSizeSupplier;
    private final List<PacketDisplayHandle> handles = new ArrayList<>();

    /** Non-null while a {@link #runBundled} body is executing — sends append here instead of flushing. */
    private List<Packet<? super ClientGamePacketListener>> bundleBuffer;

    PacketTransportSession(Player viewer, IntSupplier batchSizeSupplier) {
        this.viewer = viewer;
        this.batchSizeSupplier = batchSizeSupplier;
    }

    @Override
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

    @Override
    public void runBundled(Runnable body) {
        int batchSize = batchSizeSupplier.getAsInt();
        if (batchSize <= 0 || bundleBuffer != null) {
            // Bundling disabled, or already inside a bundle scope (no nesting) — just run.
            body.run();
            return;
        }
        bundleBuffer = new ArrayList<>();
        try {
            body.run();
            flushBundles(batchSize);
        } finally {
            bundleBuffer = null;
        }
    }

    private void flushBundles(int batchSize) {
        if (bundleBuffer.isEmpty()) return;
        int cap = Math.min(batchSize, VANILLA_BUNDLE_CAP);
        int n = bundleBuffer.size();
        for (int i = 0; i < n; i += cap) {
            List<Packet<? super ClientGamePacketListener>> slice =
                    bundleBuffer.subList(i, Math.min(n, i + cap));
            // ClientboundBundlePacket copies the iterable into its own list, so the
            // subList view is safe to pass even though we'll keep mutating bundleBuffer
            // until the outer try/finally clears it.
            ((CraftPlayer) viewer).getHandle().connection.send(new ClientboundBundlePacket(slice));
        }
        bundleBuffer.clear();
    }

    @SuppressWarnings("unchecked")
    private void send(Packet<? extends ClientGamePacketListener> packet) {
        if (bundleBuffer != null) {
            bundleBuffer.add((Packet<? super ClientGamePacketListener>) packet);
            return;
        }
        ((CraftPlayer) viewer).getHandle().connection.send(packet);
    }
}
