package io.tessera.skin;

import io.tessera.core.HeadFace;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-{@link HeadFace} in-plane rotation (degrees, multiple of 90) applied
 * to a chunk tile before {@link SkinAssembler} paints it onto the 64×64
 * skin canvas.
 *
 * <p>Why this exists separately from {@link io.tessera.assemble.FaceRotations}:
 * FaceRotations decides which face of the cube is shown outward (a 3D
 * rigid rotation of the whole cube). It does not change the in-plane
 * orientation of the texture <em>within</em> the visible face — that's
 * fixed by the skin's UV layout and the source-face's image axis
 * convention. Those two conventions don't always agree: a vanilla block's
 * UP face texture has its image-Y axis pointing one way, while the
 * head's TOP UV slot has its image-Y axis pointing another. The
 * mismatch shows up as tiles rotated 90°/180°/270° on certain faces.
 *
 * <p>Empirical workflow: set {@code debug: true} in config, run
 * {@code /tessera debug grid} (uses default Steve skin — geometry only,
 * no in-plane bias), then bake a known-uniform block (e.g. stone) and
 * watch which faces look rotated. Use {@code /tessera debug tilerot <face>
 * <degrees>} to nudge each face until tiles read upright; fold working
 * values into {@link #DEFAULTS}.
 *
 * <p>Only multiples of 90 are supported (0/90/180/270). Anything else
 * would require non-trivial resampling and isn't a pattern the vanilla
 * skin layout would ever need.
 */
public final class TileRotations {

    private static final Map<HeadFace, Integer> DEFAULTS = new EnumMap<>(HeadFace.class);
    private static final EnumMap<HeadFace, Integer> CURRENT = new EnumMap<>(HeadFace.class);

    static {
        // Initial guess: zero across the board. Tune per face as you find
        // tiles rendering rotated. Update these defaults once values have
        // been confirmed in-world.
        for (HeadFace f : HeadFace.values()) {
            DEFAULTS.put(f, 0);
        }
        CURRENT.putAll(DEFAULTS);
    }

    private TileRotations() {}

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
            throw new IllegalArgumentException("TileRotation must be a multiple of 90, got " + degrees);
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
}
