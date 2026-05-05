package org.inventivetalent.tessera.assets.model;

import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.FaceDir;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Result of {@link ModelResolver#resolve} for a full-cube block. Carries:
 *
 * <ul>
 *   <li>{@link #faces} — base texture per face (one per {@link FaceDir}),
 *       at vanilla resolution (16×16 by default).</li>
 *   <li>{@link #tintedFaces} — which base faces carry a {@code tintindex}
 *       in the model JSON and should be multiplied by the biome color. Empty
 *       for blocks with no tinting.</li>
 *   <li>{@link #overlays} — optional second-element overlay textures. Vanilla
 *       grass_block has a second full-cube element whose four side faces are a
 *       semi-transparent tinted overlay ({@code grass_block_side_overlay.png})
 *       composited on top of the untinted dirt-side base. Always tinted.</li>
 * </ul>
 *
 * <p>{@link #tinted} returns {@code true} when either {@code tintedFaces} or
 * {@code overlays} is non-empty — i.e. when the block needs a biome color at
 * bake time.
 *
 * <p>{@link #variantRotations} maps each blockstate variant key to its
 * world-space quaternion for spawn-time orientation.
 */
public final class BlockModel {

    private final BlockKey key;
    private final EnumMap<FaceDir, BufferedImage> faces;
    private final EnumSet<FaceDir> tintedFaces;
    private final EnumMap<FaceDir, BufferedImage> overlays;  // always tinted; may be empty
    private final String parentChain;
    private final Map<String, ModelResolver.VariantRotation> variantRotations;

    /** Backward-compatible: treats all faces as tinted or untinted uniformly, no overlays. */
    public BlockModel(BlockKey key, Map<FaceDir, BufferedImage> faces, boolean tinted, String parentChain) {
        this(key, faces, tinted ? EnumSet.allOf(FaceDir.class) : EnumSet.noneOf(FaceDir.class),
                Collections.emptyMap(), parentChain, Collections.emptyMap());
    }

    /** Backward-compatible: treats all faces as tinted or untinted uniformly, no overlays. */
    public BlockModel(BlockKey key, Map<FaceDir, BufferedImage> faces, boolean tinted, String parentChain,
                      Map<String, ModelResolver.VariantRotation> variantRotations) {
        this(key, faces, tinted ? EnumSet.allOf(FaceDir.class) : EnumSet.noneOf(FaceDir.class),
                Collections.emptyMap(), parentChain, variantRotations);
    }

    /** Canonical constructor with per-face tint tracking and optional overlay map. */
    public BlockModel(BlockKey key, Map<FaceDir, BufferedImage> faces,
                      Set<FaceDir> tintedFaces,
                      Map<FaceDir, BufferedImage> overlays,
                      String parentChain,
                      Map<String, ModelResolver.VariantRotation> variantRotations) {
        if (faces.size() != 6) {
            throw new IllegalArgumentException(
                    "BlockModel must have all 6 face textures, got " + faces.keySet());
        }
        this.key = key;
        this.faces = new EnumMap<>(faces);
        this.tintedFaces = tintedFaces.isEmpty()
                ? EnumSet.noneOf(FaceDir.class)
                : EnumSet.copyOf(tintedFaces);
        this.overlays = overlays.isEmpty()
                ? new EnumMap<>(FaceDir.class)
                : new EnumMap<>(overlays);
        this.parentChain = parentChain;
        this.variantRotations = Map.copyOf(variantRotations);
    }

    public BlockKey key() { return key; }
    public String parentChain() { return parentChain; }

    /** True when any face or overlay carries a {@code tintindex} — needs a biome color at bake time. */
    public boolean tinted() { return !tintedFaces.isEmpty() || !overlays.isEmpty(); }

    public BufferedImage face(FaceDir dir) { return faces.get(dir); }

    public Map<FaceDir, BufferedImage> faces() { return Map.copyOf(faces); }

    public Map<String, ModelResolver.VariantRotation> variantRotations() { return variantRotations; }

    /**
     * Return a copy of this model with per-face tinting applied:
     * <ul>
     *   <li>Faces in {@link #tintedFaces}: pixel-multiplied by {@code tintArgb}.</li>
     *   <li>Faces with an overlay: overlay is tinted then alpha-composited on
     *       top of the (possibly already tinted) base, reproducing vanilla's
     *       two-element rendering for blocks like grass_block.</li>
     *   <li>All other faces: copied unchanged.</li>
     * </ul>
     */
    public BlockModel withTint(int tintArgb) {
        if (tintArgb == 0) return this;
        EnumMap<FaceDir, BufferedImage> result = new EnumMap<>(faces);
        for (FaceDir d : FaceDir.values()) {
            boolean baseTinted = tintedFaces.contains(d);
            boolean hasOverlay = overlays.containsKey(d);
            if (!baseTinted && !hasOverlay) continue;

            BufferedImage base = faces.get(d);
            if (baseTinted) {
                base = TintApplier.multiply(base, tintArgb);
            }
            if (hasOverlay) {
                BufferedImage tintedOverlay = TintApplier.multiply(overlays.get(d), tintArgb);
                base = TintApplier.composite(base, tintedOverlay);
            }
            result.put(d, base);
        }
        // Overlays are now composited into the bases — clear them in the result.
        return new BlockModel(key, result, EnumSet.copyOf(tintedFaces),
                Collections.emptyMap(), parentChain, variantRotations);
    }
}
