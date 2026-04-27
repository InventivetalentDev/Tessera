package io.tessera.split;

import io.tessera.core.FaceDir;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-{@link FaceDir} mirror flip applied to the source block-face texture
 * <em>after</em> {@link SourceRotations} and before {@link TextureSplitter}
 * slices into tiles.
 *
 * <p>Why both rotation and flip? Rotations alone can only express 4 of the
 * 8 possible orientations of a square (the cyclic group C4). Adding a
 * mirror flip extends the reachable orientations to all 8 (the dihedral
 * group D4). Vanilla Minecraft's per-face UV conventions sometimes differ
 * by a flip (one face's image-X = world +Z, another's = world -Z), and
 * those mismatches manifest as edge-wrap mismatches between perpendicular
 * faces that no amount of rotation can fix — the wrong-rotated content
 * just moves to a different corner.
 *
 * <p>Diagnostic test: if {@code /tessera debug sourcerot &lt;face&gt; X}
 * for X = 90/180/270 only moves the bad areas around without fixing the
 * wrap, the face needs a flip instead. Try
 * {@code /tessera debug sourceflip &lt;face&gt; h} (or v, or hv).
 *
 * <p>{@link Flip#NONE NONE} = identity (default). H mirrors image-X axis,
 * V mirrors image-Y axis, HV mirrors both (equivalent to a 180° rotation —
 * but exposed for completeness so combinations with sourcerot cover all 8
 * orientations cleanly).
 *
 * <p>Bake-time only; changing values auto-invalidates the registry so the
 * next test re-bakes with the new orientation.
 */
public final class SourceFlips {

    public enum Flip {
        NONE, H, V, HV;

        public static Flip parse(String s) {
            return switch (s.toLowerCase(java.util.Locale.ROOT)) {
                case "none", "off", "" -> NONE;
                case "h", "horizontal", "flipx" -> H;
                case "v", "vertical", "flipy" -> V;
                case "hv", "vh", "both", "flipxy" -> HV;
                default -> throw new IllegalArgumentException("Unknown flip: " + s + " (try none|h|v|hv)");
            };
        }
    }

    private static final Map<FaceDir, Flip> DEFAULTS = new EnumMap<>(FaceDir.class);
    private static final EnumMap<FaceDir, Flip> CURRENT = new EnumMap<>(FaceDir.class);

    static {
        for (FaceDir f : FaceDir.values()) DEFAULTS.put(f, Flip.NONE);
        CURRENT.putAll(DEFAULTS);
    }

    private SourceFlips() {}

    public static Flip of(FaceDir face) {
        Flip v = CURRENT.get(face);
        return v == null ? Flip.NONE : v;
    }

    public static Flip defaultOf(FaceDir face) {
        Flip v = DEFAULTS.get(face);
        return v == null ? Flip.NONE : v;
    }

    public static void set(FaceDir face, Flip flip) {
        CURRENT.put(face, flip);
    }

    public static void reset(FaceDir face) {
        CURRENT.put(face, DEFAULTS.get(face));
    }

    public static void resetAll() {
        CURRENT.clear();
        CURRENT.putAll(DEFAULTS);
    }
}
