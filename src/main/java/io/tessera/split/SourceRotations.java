package io.tessera.split;

import io.tessera.core.FaceDir;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-{@link FaceDir} rotation (degrees, multiple of 90) applied to the
 * <em>source block-face texture</em> before {@link TextureSplitter} slices
 * it into tiles.
 *
 * <p>This is the "rotate the whole block face" knob. Distinct from
 * {@link io.tessera.skin.TileRotations}, which rotates each individual
 * chunk's tile in-plane within its head face slot. If the entire UP face
 * of stone looks rotated 90° when rendered (i.e. the texture's "north"
 * edge ends up on the east side of the block), set
 * {@code sourcerot up 90} (or 270 — try both signs) and re-bake.
 *
 * <p>Why this exists: vanilla Minecraft has fiddly per-face UV conventions
 * that don't always match the obvious mental model (DOWN's image-Y axis
 * goes one way; UP's goes the other; etc.). My {@link TextureSplitter#split
 * sourceTile} mapping is a best-guess; per-face overrides let you fix
 * specific axes without rewriting the splitter.
 *
 * <p>Implementation note: rotating the source <em>then</em> slicing is
 * mathematically equivalent to permuting which chunk grabs which tile, but
 * far simpler — one BufferedImage rotation per face vs. a permuted
 * sourceTile lookup. Used at bake time only; changing values
 * auto-invalidates the registry so the next test re-bakes with new tiles.
 */
public final class SourceRotations {

    private static final Map<FaceDir, Integer> DEFAULTS = new EnumMap<>(FaceDir.class);
    private static final EnumMap<FaceDir, Integer> CURRENT = new EnumMap<>(FaceDir.class);

    static {
        for (FaceDir f : FaceDir.values()) DEFAULTS.put(f, 0);
        CURRENT.putAll(DEFAULTS);
    }

    private SourceRotations() {}

    public static int of(FaceDir face) {
        Integer v = CURRENT.get(face);
        return v == null ? 0 : v;
    }

    public static int defaultOf(FaceDir face) {
        Integer v = DEFAULTS.get(face);
        return v == null ? 0 : v;
    }

    public static void set(FaceDir face, int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        if (normalized % 90 != 0) {
            throw new IllegalArgumentException("SourceRotation must be a multiple of 90, got " + degrees);
        }
        CURRENT.put(face, normalized);
    }

    public static void reset(FaceDir face) {
        CURRENT.put(face, DEFAULTS.get(face));
    }

    public static void resetAll() {
        CURRENT.clear();
        CURRENT.putAll(DEFAULTS);
    }
}
