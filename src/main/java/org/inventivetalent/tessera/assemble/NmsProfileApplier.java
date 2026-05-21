package org.inventivetalent.tessera.assemble;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
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
 * Mojang's authlib into a {@code CraftPlayerProfile} and sets it via
 * Bukkit's standard {@link SkullMeta#setOwnerProfile}. Preserves both
 * the texture value and the MineSkin signature byte-for-byte.
 *
 * <p>Two authlib generations are supported because the Paper dev bundle
 * we compile against ships an older authlib than what some runtime
 * servers carry:
 * <ul>
 *   <li><b>Modern</b> (record-based GameProfile, e.g. user-reported
 *       Spigot 1.21): {@code PropertyMap(Multimap)} +
 *       {@code GameProfile(UUID, String, PropertyMap)} +
 *       {@code properties()} accessor.</li>
 *   <li><b>Legacy</b> (bean-style GameProfile, Paper dev bundle 1.21.4):
 *       {@code PropertyMap()} no-arg + {@code GameProfile(UUID, String)}
 *       + {@code getProperties()} accessor.</li>
 * </ul>
 * Both API generations are probed at class load; whichever combination
 * works is used.
 *
 * <p>Authlib classes are accessed via reflection (not direct import) so
 * the bytecode of this applier doesn't link to a specific authlib
 * generation at compile time. Guava's {@code Multimap} is stable enough
 * to use as a direct import.
 */
final class NmsProfileApplier implements ProfileApplier {

    private static final Class<?> GAME_PROFILE_CLASS;
    private static final Class<?> PROPERTY_MAP_CLASS;
    private static final Constructor<?> PROPERTY_CTOR;
    /** Modern: {@code PropertyMap(Multimap)}. */
    private static final Constructor<?> PROPERTY_MAP_CTOR_MULTIMAP;
    /** Modern: {@code GameProfile(UUID, String, PropertyMap)}. */
    private static final Constructor<?> GAME_PROFILE_CTOR_3ARG;
    /** Legacy: {@code GameProfile(UUID, String)}. */
    private static final Constructor<?> GAME_PROFILE_CTOR_2ARG;
    /** {@code properties()} (record) or {@code getProperties()} (bean). */
    private static final Method GET_PROPERTIES_METHOD;
    /** {@code PropertyMap.put(K, V)} via Multimap. */
    private static final Method PROPERTY_MAP_PUT;
    private static final Throwable INIT_ERROR;

    static {
        Class<?> gpClass = null;
        Class<?> propMapClass = null;
        Constructor<?> propCtor = null;
        Constructor<?> pmMultimapCtor = null;
        Constructor<?> gp3 = null;
        Constructor<?> gp2 = null;
        Method getProps = null;
        Method propMapPut = null;
        Throwable err = null;
        try {
            gpClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propClass = Class.forName("com.mojang.authlib.properties.Property");
            propMapClass = Class.forName("com.mojang.authlib.properties.PropertyMap");

            propCtor = propClass.getConstructor(String.class, String.class, String.class);
            propMapPut = propMapClass.getMethod("put", Object.class, Object.class);

            try { pmMultimapCtor = propMapClass.getConstructor(Multimap.class); }
            catch (NoSuchMethodException ignored) {}

            try { gp3 = gpClass.getConstructor(UUID.class, String.class, propMapClass); }
            catch (NoSuchMethodException ignored) {}
            try { gp2 = gpClass.getConstructor(UUID.class, String.class); }
            catch (NoSuchMethodException ignored) {}

            try { getProps = gpClass.getMethod("properties"); }
            catch (NoSuchMethodException ignored) {
                try { getProps = gpClass.getMethod("getProperties"); }
                catch (NoSuchMethodException ignored2) {}
            }

            boolean modernViable = pmMultimapCtor != null && gp3 != null;
            boolean legacyViable = gp2 != null && getProps != null;
            if (!modernViable && !legacyViable) {
                throw new NoSuchMethodException(
                        "No viable GameProfile construction path: this authlib version has"
                                + " neither (UUID, String, PropertyMap) + PropertyMap(Multimap)"
                                + " nor (UUID, String) + properties()/getProperties().");
            }
        } catch (Throwable t) {
            err = t;
        }
        GAME_PROFILE_CLASS = gpClass;
        PROPERTY_MAP_CLASS = propMapClass;
        PROPERTY_CTOR = propCtor;
        PROPERTY_MAP_CTOR_MULTIMAP = pmMultimapCtor;
        GAME_PROFILE_CTOR_3ARG = gp3;
        GAME_PROFILE_CTOR_2ARG = gp2;
        GET_PROPERTIES_METHOD = getProps;
        PROPERTY_MAP_PUT = propMapPut;
        INIT_ERROR = err;
    }

    private final Logger logger;
    private volatile Constructor<?> craftPlayerProfileCtor;
    private volatile boolean ctorResolved;
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
        String step = "init";
        try {
            step = "build-property";
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
                logger.log(Level.WARNING, "[profile] Profile-apply failed at step '"
                        + step + "': " + cause.getClass().getName()
                        + (cause.getMessage() == null ? "" : ": " + cause.getMessage())
                        + ". Suppressing further warnings — heads may render blank.", cause);
            }
            return false;
        }
    }

    private Object buildGameProfile(Object property) throws ReflectiveOperationException {
        // Modern path: build PropertyMap from a Multimap, then 3-arg GameProfile.
        // The 2-arg legacy GameProfile's properties() may be a shared/immutable
        // instance on modern authlib (Spigot 1.21+), so prefer the explicit
        // PropertyMap construction.
        if (PROPERTY_MAP_CTOR_MULTIMAP != null && GAME_PROFILE_CTOR_3ARG != null) {
            Multimap<String, Object> backing = LinkedListMultimap.create();
            backing.put("textures", property);
            Object propMap = PROPERTY_MAP_CTOR_MULTIMAP.newInstance(backing);
            return GAME_PROFILE_CTOR_3ARG.newInstance(UUID.randomUUID(), "tessera", propMap);
        }
        // Legacy path: 2-arg GameProfile + mutate its property map. Used on the
        // Paper dev bundle's older authlib.
        Object profile = GAME_PROFILE_CTOR_2ARG.newInstance(UUID.randomUUID(), "tessera");
        Object pm = GET_PROPERTIES_METHOD.invoke(profile);
        if (pm == null) {
            throw new IllegalStateException(
                    "GameProfile(UUID, String).properties() returned null — can't add textures");
        }
        PROPERTY_MAP_PUT.invoke(pm, "textures", property);
        return profile;
    }

    private Constructor<?> resolveCraftProfileCtor() {
        if (ctorResolved) return craftPlayerProfileCtor;
        synchronized (this) {
            if (ctorResolved) return craftPlayerProfileCtor;
            Class<?> cls = findCraftPlayerProfileClass();
            if (cls == null) {
                if (!warnedOnce) {
                    warnedOnce = true;
                    logger.warning("[profile] CraftPlayerProfile class not found"
                            + " on this server. Heads will render blank.");
                }
            } else {
                try {
                    craftPlayerProfileCtor = cls.getConstructor(GAME_PROFILE_CLASS);
                } catch (NoSuchMethodException e) {
                    if (!warnedOnce) {
                        warnedOnce = true;
                        logger.warning("[profile] " + cls.getName()
                                + " has no public (GameProfile) constructor;"
                                + " heads will render blank.");
                    }
                }
            }
            ctorResolved = true;
            return craftPlayerProfileCtor;
        }
    }

    private static Class<?> findCraftPlayerProfileClass() {
        try {
            return Class.forName("org.bukkit.craftbukkit.profile.CraftPlayerProfile");
        } catch (ClassNotFoundException e) {
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
