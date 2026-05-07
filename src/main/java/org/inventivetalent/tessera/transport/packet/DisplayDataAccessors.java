package org.inventivetalent.tessera.transport.packet;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reflection cache for Display's private EntityDataAccessor fields.
 * Populated once at class-load time; {@link #available} is false if any
 * reflection step failed (e.g. after an MC update renamed a field).
 */
final class DisplayDataAccessors {

    static final boolean available;

    static final EntityDataAccessor<Integer> INTERP_START_DELTA_TICKS;
    static final EntityDataAccessor<Integer> INTERP_DURATION;
    static final EntityDataAccessor<Vector3f> TRANSLATION;
    static final EntityDataAccessor<Vector3f> SCALE;
    static final EntityDataAccessor<Quaternionf> LEFT_ROTATION;
    static final EntityDataAccessor<Quaternionf> RIGHT_ROTATION;
    static final EntityDataAccessor<Float> VIEW_RANGE;
    static final EntityDataAccessor<net.minecraft.world.item.ItemStack> ITEM_STACK;
    static final EntityDataAccessor<Byte> ITEM_DISPLAY;

    // Fake entity IDs descend from MAX_VALUE; real server entity IDs ascend from 0.
    // The two ranges will never meet in practice.
    private static final AtomicInteger FAKE_ID_COUNTER = new AtomicInteger(Integer.MAX_VALUE);

    static int nextEntityId() {
        return FAKE_ID_COUNTER.getAndDecrement();
    }

    static {
        EntityDataAccessor<Integer> interpStart = null, interpDuration = null;
        EntityDataAccessor<Vector3f> translation = null, scale = null;
        EntityDataAccessor<Quaternionf> leftRot = null, rightRot = null;
        EntityDataAccessor<Float> viewRange = null;
        EntityDataAccessor<net.minecraft.world.item.ItemStack> itemStack = null;
        EntityDataAccessor<Byte> itemDisplay = null;
        boolean ok = false;

        try {
            interpStart   = field(Display.class, "DATA_TRANSFORMATION_INTERPOLATION_START_DELTA_TICKS_ID");
            interpDuration = field(Display.class, "DATA_TRANSFORMATION_INTERPOLATION_DURATION_ID");
            translation   = field(Display.class, "DATA_TRANSLATION_ID");
            scale         = field(Display.class, "DATA_SCALE_ID");
            leftRot       = field(Display.class, "DATA_LEFT_ROTATION_ID");
            rightRot      = field(Display.class, "DATA_RIGHT_ROTATION_ID");
            viewRange     = field(Display.class, "DATA_VIEW_RANGE_ID");

            Class<?> itemDisplayCls = Class.forName("net.minecraft.world.entity.Display$ItemDisplay");
            itemStack   = field(itemDisplayCls, "DATA_ITEM_STACK_ID");
            itemDisplay = field(itemDisplayCls, "DATA_ITEM_DISPLAY_ID");
            ok = true;
        } catch (Exception ignored) {
            // PacketDisplayTransport.isAvailable() returns false; plugin auto-falls back to Bukkit.
        }

        INTERP_START_DELTA_TICKS = interpStart;
        INTERP_DURATION          = interpDuration;
        TRANSLATION              = translation;
        SCALE                    = scale;
        LEFT_ROTATION            = leftRot;
        RIGHT_ROTATION           = rightRot;
        VIEW_RANGE               = viewRange;
        ITEM_STACK               = itemStack;
        ITEM_DISPLAY             = itemDisplay;
        available                = ok;
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityDataAccessor<T> field(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return (EntityDataAccessor<T>) f.get(null);
    }

    private DisplayDataAccessors() {}
}
