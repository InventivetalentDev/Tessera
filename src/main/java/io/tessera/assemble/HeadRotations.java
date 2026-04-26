package io.tessera.assemble;

import io.tessera.core.FaceDir;
import io.tessera.core.HeadFace;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.Map;

/**
 * Runtime in-place rotation of a chunk cube around its outward-facing axis.
 * Applied <em>after</em> {@link FaceRotations} when building the chunk
 * ItemDisplay's right-rotation in {@link FakeBlockFactory}.
 *
 * <p>This is the runtime cousin of {@link io.tessera.skin.TileRotations}:
 * both visually rotate the texture on the visible face by 90/180/270°,
 * but {@code HeadRotations} does it by spinning the cube (no re-bake
 * needed — instant feedback via {@code /tessera debug headrot}), while
 * {@code TileRotations} does it by rotating the source tile before paint
 * (requires re-baking heads.json since the PNG bytes change).
 *
 * <p>Use {@code headrot} for fast empirical tuning. Once you find the
 * right values, decide whether to leave them as runtime rotations
 * (cheap quaternion mul per spawn) or fold them into {@link
 * io.tessera.skin.TileRotations}'s defaults (zero runtime cost; one
 * re-bake to apply).
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
     * Compose the runtime spin into {@code base} (which should be
     * {@link FaceRotations#of(HeadFace)}). The spin rotates around the
     * world-axis normal of {@code primaryFaceDir} so the texture spins
     * in the visible face's plane after FaceRotations places that face
     * outward.
     *
     * <p>Composition order: {@code spin * base}. Bukkit applies the
     * right-rotation to vertices first ({@code v' = R * v}), so with
     * {@code R = spin * base} a vertex is first base-rotated (correct
     * face shows), then spun around the now-outward axis. This matches
     * the visual effect of "spinning the texture on the visible face".
     */
    public static Quaternionf compose(HeadFace face, FaceDir primary, Quaternionf base) {
        int deg = of(face);
        if (deg == 0) return base;
        Vector3f axis = new Vector3f(primary.dx, primary.dy, primary.dz);
        Quaternionf spin = new Quaternionf().fromAxisAngleDeg(axis, deg);
        return spin.mul(base, new Quaternionf());
    }
}
