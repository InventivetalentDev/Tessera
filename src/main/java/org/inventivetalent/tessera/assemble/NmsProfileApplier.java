package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.skin.HeadSkin;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reflection-based profile applier for non-Paper servers. Goes through
 * Mojang's authlib ({@code com.mojang.authlib.GameProfile}) — bundled by
 * every vanilla-derived server including Spigot — to build a profile
 * carrying the textures property, then writes it directly to the
 * {@code CraftMetaSkull.profile} field.
 *
 * <p>Preserves both the MineSkin texture {@code value} and {@code signature}
 * byte-for-byte, matching what {@link PaperProfileApplier} achieves through
 * Paper's {@code ProfileProperty} API.
 *
 * <p>Handles two field-type generations:
 * <ul>
 *   <li>MC ≤ 1.20.4: {@code CraftMetaSkull.profile} is {@code GameProfile}
 *       — set directly.</li>
 *   <li>MC 1.20.5+: field is {@code ResolvableProfile} (or any class with
 *       a single-arg {@code (GameProfile)} constructor) — wrapped before
 *       assignment. We don't hardcode the class name because Paper uses
 *       Mojang-mapped names and Spigot may differ across versions; we
 *       just look at the field's declared type at runtime.</li>
 * </ul>
 */
final class NmsProfileApplier implements ProfileApplier {

    private static final Class<?> GAME_PROFILE_CLASS;
    private static final Constructor<?> GAME_PROFILE_CTOR;
    private static final Method GET_PROPERTIES_METHOD;
    private static final Constructor<?> PROPERTY_CTOR;
    private static final Method PROPERTY_MAP_PUT;
    private static final Throwable INIT_ERROR;

    static {
        Class<?> gpClass = null;
        Constructor<?> gpCtor = null;
        Method getProps = null;
        Constructor<?> propCtor = null;
        Method propPut = null;
        Throwable err = null;
        try {
            gpClass = Class.forName("com.mojang.authlib.GameProfile");
            gpCtor = gpClass.getConstructor(UUID.class, String.class);
            // Modern Mojang authlib (~MC 1.20+) turned GameProfile into a
            // record whose accessor is properties(); older builds had the
            // legacy getProperties() bean accessor. Try the record form
            // first since it's what every supported (1.21+) server has.
            try {
                getProps = gpClass.getMethod("properties");
            } catch (NoSuchMethodException ignored) {
                getProps = gpClass.getMethod("getProperties");
            }
            Class<?> propClass = Class.forName("com.mojang.authlib.properties.Property");
            propCtor = propClass.getConstructor(String.class, String.class, String.class);
            Class<?> propMapClass = Class.forName("com.mojang.authlib.properties.PropertyMap");
            // PropertyMap is a Guava Multimap; put(K, V) returns boolean.
            propPut = propMapClass.getMethod("put", Object.class, Object.class);
        } catch (Throwable t) {
            err = t;
        }
        GAME_PROFILE_CLASS = gpClass;
        GAME_PROFILE_CTOR = gpCtor;
        GET_PROPERTIES_METHOD = getProps;
        PROPERTY_CTOR = propCtor;
        PROPERTY_MAP_PUT = propPut;
        INIT_ERROR = err;
    }

    private final Logger logger;
    /** Resolved on the first successful apply, then reused. */
    private volatile FieldBinding binding;
    private volatile boolean warnedOnce;

    NmsProfileApplier(Logger logger) {
        this.logger = logger;
        if (INIT_ERROR != null) {
            logger.log(Level.SEVERE, "[profile] Mojang authlib reflection unavailable ("
                    + INIT_ERROR.getClass().getSimpleName() + ": " + INIT_ERROR.getMessage()
                    + "). MineSkin profiles can't be applied; player heads will render blank.");
        }
    }

    @Override
    public boolean apply(SkullMeta meta, HeadSkin head) {
        if (INIT_ERROR != null) return false;
        try {
            FieldBinding b = binding;
            if (b == null || b.metaClass != meta.getClass()) {
                b = resolveBinding(meta.getClass());
                if (b == null) return false;
                binding = b;
            }
            Object profile = buildGameProfile(head.textureValue(), head.textureSignature());
            Object value = (b.wrapCtor == null) ? profile : b.wrapCtor.newInstance(profile);
            b.field.set(meta, value);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedOnce) {
                warnedOnce = true;
                logger.log(Level.WARNING, "[profile] Reflective profile-apply failed ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "). Suppressing further warnings — heads from this point on may render blank.");
            }
            return false;
        }
    }

    private Object buildGameProfile(String value, String signature) throws ReflectiveOperationException {
        Object profile = GAME_PROFILE_CTOR.newInstance(UUID.randomUUID(), "tessera");
        Object propMap = GET_PROPERTIES_METHOD.invoke(profile);
        Object property = PROPERTY_CTOR.newInstance("textures", value, signature);
        PROPERTY_MAP_PUT.invoke(propMap, "textures", property);
        return profile;
    }

    private FieldBinding resolveBinding(Class<?> metaClass) {
        Field field = findProfileField(metaClass);
        if (field == null) {
            if (!warnedOnce) {
                warnedOnce = true;
                logger.log(Level.WARNING, "[profile] No 'profile' field on " + metaClass.getName()
                        + " or any superclass — skulls will render blank.");
            }
            return null;
        }
        Class<?> fieldType = field.getType();
        if (fieldType.isAssignableFrom(GAME_PROFILE_CLASS)) {
            return new FieldBinding(metaClass, field, null);
        }
        Constructor<?> wrap = findGameProfileCtor(fieldType);
        if (wrap == null) {
            if (!warnedOnce) {
                warnedOnce = true;
                logger.log(Level.WARNING, "[profile] CraftMetaSkull.profile field has type "
                        + fieldType.getName() + " with no (GameProfile) constructor; can't apply"
                        + " MineSkin profile reflectively — heads will render blank.");
            }
            return null;
        }
        return new FieldBinding(metaClass, field, wrap);
    }

    private static Field findProfileField(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if ("profile".equals(f.getName())) {
                    f.setAccessible(true);
                    return f;
                }
            }
        }
        return null;
    }

    private static Constructor<?> findGameProfileCtor(Class<?> wrapperType) {
        for (Constructor<?> ctor : wrapperType.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 1 && params[0].isAssignableFrom(GAME_PROFILE_CLASS)) {
                ctor.setAccessible(true);
                return ctor;
            }
        }
        return null;
    }

    private record FieldBinding(Class<?> metaClass, Field field, Constructor<?> wrapCtor) {}
}
