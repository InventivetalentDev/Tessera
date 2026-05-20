package org.inventivetalent.tessera.assets.model;

import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.FaceDir;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Result of {@link ModelResolver#resolve} for a block. Carries one or more
 * named shapes plus a variant-key → (shape, rotation) lookup table.
 *
 * <p>Most blocks have one shape:
 * <ul>
 *   <li>Cubes (stone, oak_log, grass_block, …) → one {@link ShapeContent.CubeFaces}
 *       under the default shape key. Tint + overlays are supported on this
 *       shape only.</li>
 *   <li>Single-element non-cubes (slabs, daylight detector) → one
 *       {@link ShapeContent.VoxelizedShape} with the pre-voxelized chunks.</li>
 * </ul>
 *
 * <p>Stairs reference three distinct models (straight, inner-corner,
 * outer-corner) across their blockstate; each gets its own
 * {@link ShapeContent.VoxelizedShape} keyed by the model path. The variant
 * table maps each {@code "facing=…,half=…,shape=…"} variant string to one
 * of those shape keys + the runtime rotation to apply.
 */
public final class BlockModel {

    /**
     * Stable identifier for a single-shape block (cubes + single-model
     * non-cubes). Multi-shape blocks (stairs) use model paths as keys
     * instead.
     */
    public static final String DEFAULT_SHAPE_KEY = "default";

    /**
     * Variant → (shape, rotation) binding. The {@code rotation} is applied
     * to the lattice as a whole at spawn time (block-level), while
     * {@code shapeKey} selects which voxelization to spawn.
     */
    public record VariantBinding(String shapeKey, ModelResolver.VariantRotation rotation) {}

    private final BlockKey key;
    private final String defaultShapeKey;
    private final Map<String, ShapeContent> shapes;
    private final Map<String, VariantBinding> variants;
    private final String parentChain;

    public BlockModel(BlockKey key,
                      String defaultShapeKey,
                      Map<String, ShapeContent> shapes,
                      Map<String, VariantBinding> variants,
                      String parentChain) {
        if (shapes.isEmpty()) throw new IllegalArgumentException("BlockModel must have at least one shape");
        if (!shapes.containsKey(defaultShapeKey)) {
            throw new IllegalArgumentException("defaultShapeKey '" + defaultShapeKey
                    + "' not in shapes " + shapes.keySet());
        }
        this.key = key;
        this.defaultShapeKey = defaultShapeKey;
        this.shapes = Map.copyOf(shapes);
        this.variants = variants.isEmpty() ? Collections.emptyMap() : Map.copyOf(variants);
        this.parentChain = parentChain;
    }

    /** Legacy single-shape (cube) convenience: all faces tinted iff {@code tinted}, no overlays. */
    public BlockModel(BlockKey key, Map<FaceDir, BufferedImage> faces, boolean tinted,
                      String parentChain, Map<String, ModelResolver.VariantRotation> variantRotations) {
        this(key, faces,
                tinted ? EnumSet.allOf(FaceDir.class) : EnumSet.noneOf(FaceDir.class),
                Collections.emptyMap(), parentChain, variantRotations);
    }

    /** Legacy single-shape (cube) convenience with per-face tinting + overlays. */
    public BlockModel(BlockKey key, Map<FaceDir, BufferedImage> faces,
                      Set<FaceDir> tintedFaces, Map<FaceDir, BufferedImage> overlays,
                      String parentChain,
                      Map<String, ModelResolver.VariantRotation> variantRotations) {
        this(key, DEFAULT_SHAPE_KEY,
                Map.of(DEFAULT_SHAPE_KEY, new ShapeContent.CubeFaces(faces, tintedFaces, overlays)),
                toBindings(variantRotations),
                parentChain);
    }

    private static Map<String, VariantBinding> toBindings(
            Map<String, ModelResolver.VariantRotation> rotations) {
        if (rotations == null || rotations.isEmpty()) return Collections.emptyMap();
        LinkedHashMap<String, VariantBinding> out = new LinkedHashMap<>(rotations.size() * 2);
        for (Map.Entry<String, ModelResolver.VariantRotation> e : rotations.entrySet()) {
            out.put(e.getKey(), new VariantBinding(DEFAULT_SHAPE_KEY, e.getValue()));
        }
        return out;
    }

    /**
     * Convenience accessor for cube-shaped blocks: returns the face image
     * for the default cube shape, or {@code null} if the default shape isn't
     * a {@link ShapeContent.CubeFaces}.
     */
    public BufferedImage face(FaceDir d) {
        ShapeContent sc = defaultShape();
        return sc instanceof ShapeContent.CubeFaces cf ? cf.face(d) : null;
    }

    public BlockKey key() { return key; }
    public String parentChain() { return parentChain; }
    public String defaultShapeKey() { return defaultShapeKey; }
    public Map<String, ShapeContent> shapes() { return shapes; }
    public Map<String, VariantBinding> variants() { return variants; }

    public ShapeContent defaultShape() { return shapes.get(defaultShapeKey); }

    /**
     * True when any shape is a tinted {@link ShapeContent.CubeFaces}. The
     * bake path applies a biome tint before splitting only when this is
     * true (and only to the tinted shapes). Voxelized shapes never tint.
     */
    public boolean tinted() {
        for (ShapeContent sc : shapes.values()) {
            if (sc instanceof ShapeContent.CubeFaces cf && cf.isTinted()) return true;
        }
        return false;
    }

    /**
     * Return a copy with biome tint applied to every tinted {@link
     * ShapeContent.CubeFaces} shape. Voxelized shapes pass through
     * unchanged (no vanilla slab/stair has a tintindex; if a future shape
     * needs tinting it'll need its own withTint path).
     */
    public BlockModel withTint(int tintArgb) {
        if (tintArgb == 0 || !tinted()) return this;
        LinkedHashMap<String, ShapeContent> tinted = new LinkedHashMap<>();
        for (Map.Entry<String, ShapeContent> e : shapes.entrySet()) {
            if (e.getValue() instanceof ShapeContent.CubeFaces cf) {
                tinted.put(e.getKey(), cf.withTint(tintArgb));
            } else {
                tinted.put(e.getKey(), e.getValue());
            }
        }
        return new BlockModel(key, defaultShapeKey, tinted, variants, parentChain);
    }

    /**
     * Synthesized {@code variantKey → VariantRotation} table for callers that
     * predate multi-shape (e.g. anything reading the rotation but not yet
     * threading shapeKey). Pulls the rotation field out of each binding.
     */
    public Map<String, ModelResolver.VariantRotation> variantRotations() {
        if (variants.isEmpty()) return Collections.emptyMap();
        LinkedHashMap<String, ModelResolver.VariantRotation> out = new LinkedHashMap<>();
        for (Map.Entry<String, VariantBinding> e : variants.entrySet()) {
            out.put(e.getKey(), e.getValue().rotation());
        }
        return out;
    }
}
