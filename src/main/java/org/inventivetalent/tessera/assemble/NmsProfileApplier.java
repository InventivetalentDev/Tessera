package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.skin.HeadSkin;
import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spigot profile applier. Routes the MineSkin texture property through
 * Mojang's authlib (bundled with every vanilla-derived server) and into
 * a CraftPlayerProfile, then sets it via Bukkit's standard
 * {@code SkullMeta.setOwnerProfile}.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Build {@code com.mojang.authlib.GameProfile} with a
 *       {@code Property("textures", value, signature)} in its property map.
 *       Both value AND signature are preserved byte-for-byte.</li>
 *   <li>Invoke {@code CraftPlayerProfile(GameProfile)} (public constructor),
 *       which copies the property multimap into a Bukkit-API
 *       {@link PlayerProfile}.</li>
 *   <li>Call {@code meta.setOwnerProfile(profile)} — CraftMetaSkull then
 *       calls {@code CraftPlayerProfile.buildResolvableProfile()} which
 *       produces a {@code ResolvableProfile.Static} wrapping our
 *       GameProfile. This is the path that vanilla skull NBT uses, so
 *       textures display correctly on any client.</li>
 * </ol>
 *
 * <p>Authlib changes handled: modern Mojang authlib turned {@code GameProfile}
 * into a record, so the property accessor is {@code properties()} rather
 * than {@code getProperties()}. We try the record form first.
 *
 * <p>CraftPlayerProfile location: from MC 1.20.5+ both Paper and recent
 * Spigot ship it at {@code org.bukkit.craftbukkit.profile.CraftPlayerProfile}
 * (no version suffix); older Spigot versions had it at
 * {@code org.bukkit.craftbukkit.<version>.profile.CraftPlayerProfile}.
 * We try the unversioned name first and fall back to the versioned one
 * derived from {@code Bukkit.getServer().getClass()}.
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
            // Record accessor (modern authlib) vs. legacy bean accessor.
            try {
                getProps = gpClass.getMethod("properties");
            } catch (NoSuchMethodException ignored) {
                getProps = gpClass.getMethod("getProperties");
            }
            Class<?> propClass = Class.forName("com.mojang.authlib.properties.Property");
            propCtor = propClass.getConstructor(String.class, String.class, String.class);
            Class<?> propMapClass = Class.forName("com.mojang.authlib.properties.PropertyMap");
            // PropertyMap is a Guava Multimap; put(K, V).
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
    /** Resolved on first apply, then reused. */
    private volatile Constructor<?> craftPlayerProfileCtor;
    private volatile boolean warnedOnce;

    NmsProfileApplier(Logger logger) {
        this.logger = logger;
        if (INIT_ERROR != null) {
            logger.log(Level.SEVERE, "[profile] Mojang authlib reflection unavailable ("
                    + INIT_ERROR.getClass().getSimpleName() + ": " + INIT_ERROR.getMessage()
                    + "). Heads will render blank.");
        }
    }

    @Override
    public boolean apply(SkullMeta meta, HeadSkin head) {
        if (INIT_ERROR != null) return false;
        try {
            Object gameProfile = buildGameProfile(head.textureValue(), head.textureSignature());
            Constructor<?> ctor = resolveCraftProfileCtor();
            if (ctor == null) return false;
            PlayerProfile profile = (PlayerProfile) ctor.newInstance(gameProfile);
            meta.setOwnerProfile(profile);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warnedOnce) {
                warnedOnce = true;
                logger.log(Level.WARNING, "[profile] Reflective profile-apply failed ("
                        + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "). Suppressing further warnings — heads may render blank.");
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

    private Constructor<?> resolveCraftProfileCtor() {
        Constructor<?> cached = craftPlayerProfileCtor;
        if (cached != null) return cached;
        Class<?> cls = findCraftPlayerProfileClass();
        if (cls == null) {
            if (!warnedOnce) {
                warnedOnce = true;
                logger.log(Level.WARNING, "[profile] CraftPlayerProfile class not found on this server."
                        + " Heads will render blank.");
            }
            return null;
        }
        try {
            Constructor<?> ctor = cls.getConstructor(GAME_PROFILE_CLASS);
            craftPlayerProfileCtor = ctor;
            return ctor;
        } catch (NoSuchMethodException e) {
            if (!warnedOnce) {
                warnedOnce = true;
                logger.log(Level.WARNING, "[profile] " + cls.getName()
                        + " has no public (GameProfile) constructor — heads will render blank.");
            }
            return null;
        }
    }

    private static Class<?> findCraftPlayerProfileClass() {
        try {
            return Class.forName("org.bukkit.craftbukkit.profile.CraftPlayerProfile");
        } catch (ClassNotFoundException e) {
            // Older Spigot used per-MC-version packages
            // (org.bukkit.craftbukkit.v1_21_R3.profile.CraftPlayerProfile, etc.).
            String pkg = Bukkit.getServer().getClass().getPackage().getName();
            try {
                return Class.forName(pkg + ".profile.CraftPlayerProfile");
            } catch (ClassNotFoundException e2) {
                return null;
            }
        }
    }
}
