package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.skin.HeadSkin;
import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
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
 *       Both value AND signature preserved byte-for-byte.</li>
 *   <li>Invoke {@code CraftPlayerProfile(GameProfile)} (public constructor),
 *       which copies the property multimap into a Bukkit-API
 *       {@link PlayerProfile}.</li>
 *   <li>Call {@code meta.setOwnerProfile(profile)} — CraftMetaSkull then
 *       converts to a {@code ResolvableProfile.Static} wrapping our
 *       GameProfile. Same shape vanilla skull NBT uses, so renders on any
 *       client.</li>
 * </ol>
 *
 * <p>GameProfile construction tries the 3-arg
 * {@code (UUID, String, PropertyMap)} record-canonical constructor first
 * (modern authlib) and falls back to {@code (UUID, String)} +
 * {@code properties().put(...)} for older authlib variants.
 */
final class NmsProfileApplier implements ProfileApplier {

    private static final Class<?> GAME_PROFILE_CLASS;
    private static final Class<?> PROPERTY_CLASS;
    private static final Class<?> PROPERTY_MAP_CLASS;
    private static final Constructor<?> PROPERTY_CTOR;
    private static final Constructor<?> PROPERTY_MAP_CTOR;
    private static final Method PROPERTY_MAP_PUT;
    /** Modern authlib only: {@code GameProfile(UUID, String, PropertyMap)}. May be null. */
    private static final Constructor<?> GAME_PROFILE_3ARG_CTOR;
    /** Legacy fallback: {@code GameProfile(UUID, String)}. */
    private static final Constructor<?> GAME_PROFILE_2ARG_CTOR;
    /** Modern record accessor {@code properties()} or legacy {@code getProperties()}. */
    private static final Method GET_PROPERTIES_METHOD;
    private static final Throwable INIT_ERROR;

    static {
        Class<?> gpClass = null;
        Class<?> propClass = null;
        Class<?> propMapClass = null;
        Constructor<?> propCtor = null;
        Constructor<?> propMapCtor = null;
        Method propMapPut = null;
        Constructor<?> gp3 = null;
        Constructor<?> gp2 = null;
        Method getProps = null;
        Throwable err = null;
        try {
            gpClass = Class.forName("com.mojang.authlib.GameProfile");
            propClass = Class.forName("com.mojang.authlib.properties.Property");
            propMapClass = Class.forName("com.mojang.authlib.properties.PropertyMap");
            propCtor = propClass.getConstructor(String.class, String.class, String.class);
            propMapCtor = propMapClass.getConstructor();
            propMapPut = propMapClass.getMethod("put", Object.class, Object.class);
            try {
                gp3 = gpClass.getConstructor(UUID.class, String.class, propMapClass);
            } catch (NoSuchMethodException ignored) {
                gp3 = null;
            }
            try {
                gp2 = gpClass.getConstructor(UUID.class, String.class);
            } catch (NoSuchMethodException ignored) {
                gp2 = null;
            }
            try {
                getProps = gpClass.getMethod("properties");
            } catch (NoSuchMethodException ignored) {
                try {
                    getProps = gpClass.getMethod("getProperties");
                } catch (NoSuchMethodException ignored2) {
                    getProps = null;
                }
            }
            if (gp3 == null && (gp2 == null || getProps == null)) {
                throw new NoSuchMethodException(
                        "GameProfile has neither (UUID, String, PropertyMap) nor (UUID, String) + properties()");
            }
        } catch (Throwable t) {
            err = t;
        }
        GAME_PROFILE_CLASS = gpClass;
        PROPERTY_CLASS = propClass;
        PROPERTY_MAP_CLASS = propMapClass;
        PROPERTY_CTOR = propCtor;
        PROPERTY_MAP_CTOR = propMapCtor;
        PROPERTY_MAP_PUT = propMapPut;
        GAME_PROFILE_3ARG_CTOR = gp3;
        GAME_PROFILE_2ARG_CTOR = gp2;
        GET_PROPERTIES_METHOD = getProps;
        INIT_ERROR = err;
    }

    private final Logger logger;
    private volatile Constructor<?> craftPlayerProfileCtor;
    private volatile boolean warnedOnce;

    NmsProfileApplier(Logger logger) {
        this.logger = logger;
        if (INIT_ERROR != null) {
            logger.log(Level.SEVERE, "[profile] Mojang authlib reflection unavailable ("
                    + INIT_ERROR.getClass().getSimpleName() + ": " + INIT_ERROR.getMessage()
                    + "). Heads will render blank.", INIT_ERROR);
        }
    }

    @Override
    public boolean apply(SkullMeta meta, HeadSkin head) {
        if (INIT_ERROR != null) return false;
        String step = "build-property";
        try {
            Object property = PROPERTY_CTOR.newInstance(
                    "textures", head.textureValue(), head.textureSignature());

            step = "build-game-profile";
            Object gameProfile = buildGameProfile(property);

            step = "find-craft-player-profile";
            Constructor<?> ctor = resolveCraftProfileCtor();
            if (ctor == null) return false;

            step = "construct-craft-player-profile";
            PlayerProfile profile = (PlayerProfile) ctor.newInstance(gameProfile);

            step = "setOwnerProfile";
            meta.setOwnerProfile(profile);
            return true;
        } catch (Throwable e) {
            if (!warnedOnce) {
                warnedOnce = true;
                Throwable cause = unwrap(e);
                logger.log(Level.WARNING, "[profile] Reflective profile-apply failed at step '"
                        + step + "': " + cause.getClass().getName()
                        + (cause.getMessage() == null ? "" : ": " + cause.getMessage())
                        + ". Suppressing further warnings — heads may render blank.", cause);
            }
            return false;
        }
    }

    private Object buildGameProfile(Object property) throws ReflectiveOperationException {
        // 3-arg path: build the PropertyMap, populate it, then construct the GameProfile.
        // Preferred because it doesn't depend on what the 2-arg constructor initializes
        // properties() to (might be a shared/empty/immutable instance in newer authlib).
        if (GAME_PROFILE_3ARG_CTOR != null) {
            Object propMap = PROPERTY_MAP_CTOR.newInstance();
            PROPERTY_MAP_PUT.invoke(propMap, "textures", property);
            return GAME_PROFILE_3ARG_CTOR.newInstance(UUID.randomUUID(), "tessera", propMap);
        }
        // 2-arg fallback: construct empty, then add to its property map.
        Object profile = GAME_PROFILE_2ARG_CTOR.newInstance(UUID.randomUUID(), "tessera");
        Object propMap = GET_PROPERTIES_METHOD.invoke(profile);
        if (propMap == null) {
            throw new IllegalStateException(
                    "GameProfile(UUID, String).properties() returned null — can't add textures");
        }
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
                logger.log(Level.WARNING, "[profile] CraftPlayerProfile class not found."
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
            // Older Spigot used per-MC-version packages.
            String pkg = Bukkit.getServer().getClass().getPackage().getName();
            try {
                return Class.forName(pkg + ".profile.CraftPlayerProfile");
            } catch (ClassNotFoundException e2) {
                return null;
            }
        }
    }

    private static Throwable unwrap(Throwable t) {
        while (t instanceof InvocationTargetException ite && ite.getTargetException() != null) {
            t = ite.getTargetException();
        }
        return t;
    }
}
