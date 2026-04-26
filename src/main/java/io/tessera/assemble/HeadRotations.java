package io.tessera.assemble;

import io.tessera.core.FaceDir;
import io.tessera.core.HeadFace;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.Map;

/**
 * Runtime in-place rotation of a chunk cube around the visible face's
 * <em>cube-local</em> outward axis. Composed with {@link FaceRotations} when
 * building the chunk ItemDisplay's right-rotation in {@link FakeBlockFactory}.
 *
 * <p>Composition: {@code R = base * spin} (where {@code base = FaceRotations.of(face)}).
 * Bukkit applies {@code R*v} to mesh vertices, which by quaternion-vector
 * algebra means: first {@code spin}, then {@code base}. So the spin happens
 * in the head model's natural orientation — rotating the face's texture in
 * its own UV plane — before {@code base} reorients the whole cube so the
 * picked face ends up outward in world space. This is the visual equivalent
 * of "rotate the texture on the visible face by N degrees".
 *
 * <p>The cube-local outward axis per {@link HeadFace} comes from the
 * standard player-head skin UV layout (TOP slot is on the +Y face of the
 * head model, BOTTOM on -Y, etc.). If empirically a face spins around the
 * wrong axis, fix it in {@link #cubeLocalAxisFor}; sign issues can be
 * sidestepped by typing {@code 270} instead of {@code 90}.
 *
 * <p>This is the runtime cousin of {@link io.tessera.skin.TileRotations}:
 * both visually rotate the texture on the visible face by 90/180/270°,
 * but {@code HeadRotations} does it instantly (no re-bake), while
 * {@code TileRotations} bakes the rotation into the PNG bytes (zero
 * runtime cost; one re-bake to apply).
 */
public final class HeadRotations {

    private static final Map<HeadFace, Integer> DEFAULTS = new EnumMap<>(HeadFace.class);
    private static final EnumMap<HeadFace, Integer> CURRENT = new EnumMap<>(HeadFace.class);

    static {
        for (HeadFace f : HeadFace.values()) DEFAULTS.put(f, 0);
        CURRENT.putAll(DEFAULTS);
    }

    private HeadRotations() {}

    public static int of(HeadFace face) {
        Integer v = CURRENT.get(face);
        return v == null ? 0 : v;
    }

    public static int defaultOf(HeadFace face) {
        Integer v = DEFAULTS.get(face);
        return v == null ? 0 : v;
    }

    public static void set(HeadFace face, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized % 90 != 0) {
            throw new IllegalArgumentException("HeadRotation must be a multiple of 90, got " + degrees);
        }
        CURRENT.put(face, normalized);
    }

    public static void reset(HeadFace face) {
        CURRENT.put(face, DEFAULTS.get(face));
    }

    public static void resetAll() {
        CURRENT.clear();
        CURRENT.putAll(DEFAULTS);
    }

    /**
     * Compose {@code base} (the {@link FaceRotations} entry for {@code face})
     * with the runtime spin. Result = {@code base * spin}, so when Bukkit
     * applies it as {@code R*v} the spin runs first in cube-local space —
     * rotating the texture in the visible face's UV plane — then {@code base}
     * reorients the cube so the picked face is outward in world space.
     *
     * <p>The {@code primary} arg is unused but kept for API stability — the
     * spin axis is the head's cube-local axis for the slot, not the
     * world-axis of the FaceDir. (FaceDir vs HeadFace are 1:1 in v1 but
     * conceptually distinct.)
     */
    public static Quaternionf compose(HeadFace face, FaceDir primary, Quaternionf base) {
        int deg = of(face);
        if (deg == 0) return base;
        Vector3f axis = cubeLocalAxisFor(face);
        Quaternionf spin = new Quaternionf().fromAxisAngleDeg(axis.x, axis.y, axis.z, deg);
        // R = base * spin → vertex transform: first spin, then base.
        return new Quaternionf(base).mul(spin);
    }

    /**
     * Cube-local outward direction of each HeadFace's UV slot, treating the
     * head model as identity-rotated (no FaceRotations applied yet). +Y is
     * up in skin-UV space; FRONT is +Z (the wearer's nose direction in
     * skin-UV space, before any rendering offsets).
     */
    private static Vector3f cubeLocalAxisFor(HeadFace face) {
        return switch (face) {
            case TOP    -> new Vector3f(0,  1, 0);
            case BOTTOM -> new Vector3f(0, -1, 0);
            case FRONT  -> new Vector3f(0,  0, 1);
            case BACK   -> new Vector3f(0,  0,-1);
            case LEFT   -> new Vector3f(-1, 0, 0);
            case RIGHT  -> new Vector3f(1,  0, 0);
        };
    }
}

