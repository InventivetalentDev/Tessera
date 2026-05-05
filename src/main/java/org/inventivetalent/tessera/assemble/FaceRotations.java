package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.core.HeadFace;
import org.joml.Quaternionf;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-face {@code rightRotation} table for chunk ItemDisplays — picks which
 * side of the player-head cube is shown to the camera.
 *
 * <p>Stored as Euler (X, Y, Z) degrees rather than quaternions so
 * {@code /tessera debug face} can round-trip values the user typed exactly:
 * {@code Quaternionf#getEulerAnglesXYZ} is ambiguous at 180° (a 180° rotation
 * around Y is indistinguishable from 180° around X plus 180° around Z).
 * Keeping degrees as the source of truth removes the ambiguity.
 *
 * <p>Defaults verified in-world via Mosaikin's debug commands. Key empirical
 * finding: an ItemDisplay carrying a player-head ItemStack with
 * {@code ItemDisplayTransform.NONE} renders the head's face (the +Z face of
 * the cube in skin-UV space) pointing at <em>-Z</em>, opposite the naive
 * "head faces +Z" mental model — which is why most entries here carry a 180°
 * yaw compared to a textbook axis-to-axis table. Pair these with
 * {@code SkinAssembler}'s BOTTOM pre-mirror — without it, the BOTTOM texture
 * renders horizontally flipped regardless of what rotation we apply here.
 *
 * <p>Mutable for in-game tuning. Reads/writes happen on the main thread, so
 * no synchronization is needed.
 */
public final class FaceRotations {

    public record Euler(float xDeg, float yDeg, float zDeg) {
        public Quaternionf toQuat() {
            return new Quaternionf()
                    .rotateX((float) Math.toRadians(xDeg))
                    .rotateY((float) Math.toRadians(yDeg))
                    .rotateZ((float) Math.toRadians(zDeg));
        }
    }

    private static final Map<HeadFace, Euler> DEFAULTS = new EnumMap<>(HeadFace.class);
    private static final EnumMap<HeadFace, Euler> CURRENT = new EnumMap<>(HeadFace.class);

    static {
        // From Mosaikin, verified in-world with /debug tint + /debug face on Paper 1.20.6.
        // Check on first runServer with /tessera debug grid; bake into different defaults
        // here if Paper's ItemDisplay renderer shifts in a future version.
        DEFAULTS.put(HeadFace.FRONT,  new Euler(  0f,  180f, 0f));
        DEFAULTS.put(HeadFace.BACK,   new Euler(  0f,    0f, 0f));
        DEFAULTS.put(HeadFace.LEFT,   new Euler(  0f,   90f, 0f));
        DEFAULTS.put(HeadFace.RIGHT,  new Euler(  0f,  -90f, 0f));
        DEFAULTS.put(HeadFace.TOP,    new Euler( 90f,  180f, 0f));
        DEFAULTS.put(HeadFace.BOTTOM, new Euler(-90f,    0f, 0f));
        CURRENT.putAll(DEFAULTS);
    }

    private FaceRotations() {}

    public static Quaternionf of(HeadFace face) {
        Euler e = CURRENT.get(face);
        return e == null ? new Quaternionf() : e.toQuat();
    }

    public static Euler eulerOf(HeadFace face) {
        Euler e = CURRENT.get(face);
        return e == null ? new Euler(0f, 0f, 0f) : e;
    }

    public static Euler defaultEulerOf(HeadFace face) {
        Euler e = DEFAULTS.get(face);
        return e == null ? new Euler(0f, 0f, 0f) : e;
    }

    public static void set(HeadFace face, float xDeg, float yDeg, float zDeg) {
        CURRENT.put(face, new Euler(xDeg, yDeg, zDeg));
    }

    public static void reset(HeadFace face) {
        CURRENT.put(face, DEFAULTS.get(face));
    }

    public static void resetAll() {
        CURRENT.clear();
        CURRENT.putAll(DEFAULTS);
    }
}
