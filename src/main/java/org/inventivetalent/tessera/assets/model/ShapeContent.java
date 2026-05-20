package org.inventivetalent.tessera.assets.model;

import org.inventivetalent.tessera.core.ChunkSpec;
import org.inventivetalent.tessera.core.FaceDir;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Data for one model shape after resolution. Either:
 * <ul>
 *   <li>{@link CubeFaces} — a full-cube block ({@code from=[0,0,0]
 *       to=[16,16,16]}). Carries the same per-face textures + tint/overlay
 *       data that pre-multishape Tessera handled. Split into chunks at bake
 *       time by {@code TextureSplitter} (after biome tint is applied).</li>
 *   <li>{@link VoxelizedShape} — anything else. The {@link ShapeVoxelizer}
 *       has already cut the model's elements into per-cell {@link ChunkSpec}s
 *       at bake-resolve time; the bake pipeline consumes them directly.
 *       Tinting + overlays are not supported on this path in v1 (no vanilla
 *       slab/stair has a {@code tintindex}).</li>
 * </ul>
 */
public sealed interface ShapeContent permits ShapeContent.CubeFaces, ShapeContent.VoxelizedShape {

    /** Carries the 16×16 per-face base textures of a single full-cube element. */
    final class CubeFaces implements ShapeContent {
        private final EnumMap<FaceDir, BufferedImage> faces;
        private final EnumSet<FaceDir> tintedFaces;
        private final EnumMap<FaceDir, BufferedImage> overlays;

        public CubeFaces(Map<FaceDir, BufferedImage> faces,
                         Set<FaceDir> tintedFaces,
                         Map<FaceDir, BufferedImage> overlays) {
            if (faces.size() != 6) {
                throw new IllegalArgumentException(
                        "CubeFaces must have all 6 face textures, got " + faces.keySet());
            }
            this.faces = new EnumMap<>(faces);
            this.tintedFaces = tintedFaces.isEmpty()
                    ? EnumSet.noneOf(FaceDir.class)
                    : EnumSet.copyOf(tintedFaces);
            this.overlays = overlays.isEmpty()
                    ? new EnumMap<>(FaceDir.class)
                    : new EnumMap<>(overlays);
        }

        public BufferedImage face(FaceDir d) { return faces.get(d); }
        public Map<FaceDir, BufferedImage> faces() { return Collections.unmodifiableMap(faces); }
        public Set<FaceDir> tintedFaces() { return Collections.unmodifiableSet(tintedFaces); }
        public Map<FaceDir, BufferedImage> overlays() { return Collections.unmodifiableMap(overlays); }

        /** True when any face or overlay carries a {@code tintindex}. */
        public boolean isTinted() { return !tintedFaces.isEmpty() || !overlays.isEmpty(); }

        /**
         * Return a copy with biome tint applied to tinted faces and any
         * tinted overlays composited onto their bases. The result has no
         * remaining overlays and no tinted-face flags — i.e. {@link #isTinted}
         * returns false on the result.
         */
        public CubeFaces withTint(int tintArgb) {
            if (tintArgb == 0 || !isTinted()) return this;
            EnumMap<FaceDir, BufferedImage> result = new EnumMap<>(faces);
            for (FaceDir d : FaceDir.values()) {
                boolean baseTinted = tintedFaces.contains(d);
                boolean hasOverlay = overlays.containsKey(d);
                if (!baseTinted && !hasOverlay) continue;

                BufferedImage base = faces.get(d);
                if (baseTinted) base = TintApplier.multiply(base, tintArgb);
                if (hasOverlay) {
                    BufferedImage tintedOverlay = TintApplier.multiply(overlays.get(d), tintArgb);
                    base = TintApplier.composite(base, tintedOverlay);
                }
                result.put(d, base);
            }
            // Preserve the tintedFaces flag — the post-tint model is "still
            // a tinted block" semantically (matches pre-multishape behaviour
            // that callers + tests pin down). Overlays are dropped because
            // they're now baked into the face pixels.
            return new CubeFaces(result, tintedFaces, Collections.emptyMap());
        }
    }

    /**
     * Pre-voxelized chunks for a non-cube shape. {@link #chunks} is what the
     * bake pipeline's {@code HeadSkinPacker} consumes directly — no further
     * splitting needed.
     */
    record VoxelizedShape(List<ChunkSpec> chunks) implements ShapeContent {
        public VoxelizedShape {
            chunks = List.copyOf(chunks);
        }
    }
}
