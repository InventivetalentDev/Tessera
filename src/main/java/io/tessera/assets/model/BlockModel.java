package io.tessera.assets.model;

import io.tessera.core.BlockKey;
import io.tessera.core.FaceDir;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * Result of {@link ModelResolver#resolve} for a full-cube block. Carries the
 * 6 face textures (one per {@link FaceDir}) at vanilla resolution (16x16 px
 * by default). Non-cube blocks return {@link java.util.Optional#empty()}
 * from the resolver and don't reach this class.
 *
 * <p>{@link #tinted} flags blocks that need biome-tint applied at render time
 * (grass_block top, oak_leaves, etc.). v1 declines to render tinted blocks
 * since we'd need biome-aware shading; v2 may support it.
 */
public final class BlockModel {

    private final BlockKey key;
    private final EnumMap<FaceDir, BufferedImage> faces;
    private final boolean tinted;
    private final String parentChain;

    public BlockModel(BlockKey key, Map<FaceDir, BufferedImage> faces, boolean tinted, String parentChain) {
        if (faces.size() != 6) {
            throw new IllegalArgumentException(
                    "BlockModel must have all 6 face textures, got " + faces.keySet());
        }
        this.key = key;
        this.faces = new EnumMap<>(faces);
        this.tinted = tinted;
        this.parentChain = parentChain;
    }

    public BlockKey key() { return key; }
    public boolean tinted() { return tinted; }
    public String parentChain() { return parentChain; }

    public BufferedImage face(FaceDir dir) {
        return faces.get(dir);
    }

    public Map<FaceDir, BufferedImage> faces() {
        return Map.copyOf(faces);
    }
}
