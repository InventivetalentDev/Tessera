package io.tessera.assets.model;

import io.tessera.core.BlockKey;
import io.tessera.core.FaceDir;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockModelWithTintTest {

    @Test
    void withZeroTintReturnsSameInstance() {
        BlockModel model = sampleModel(0xFFFFFFFF);
        assertSame(model, model.withTint(0));
    }

    @Test
    void withNonZeroTintReturnsCopy() {
        BlockModel model = sampleModel(0xFFFFFFFF);
        BlockModel tinted = model.withTint(0xFF71A74D);
        assertNotSame(model, tinted);
    }

    @Test
    void withNonZeroTintMultipliesAllSixFaces() {
        // Tint applied uniformly to every face — v1 simplification noted in
        // BlockModel.withTint javadoc. Per-face tintindex parsing is deferred.
        BlockModel model = sampleModel(0xFFFFFFFF);
        BlockModel tinted = model.withTint(0xFF71A74D);
        for (FaceDir d : FaceDir.values()) {
            assertEquals(0xFF71A74D, tinted.face(d).getRGB(0, 0),
                    "tint not applied to face " + d);
        }
    }

    @Test
    void withTintPreservesMetadata() {
        BlockModel model = sampleModel(0xFFFFFFFF);
        BlockModel tinted = model.withTint(0xFF808080);
        assertEquals(model.key(), tinted.key());
        assertEquals(model.tinted(), tinted.tinted());
        assertEquals(model.parentChain(), tinted.parentChain());
        assertEquals(model.variantRotations(), tinted.variantRotations());
    }

    @Test
    void withTintLeavesSourceFacesUntouched() {
        BlockModel model = sampleModel(0xFFFFFFFF);
        model.withTint(0xFF71A74D);
        // Original face image must not have been mutated in place.
        assertEquals(0xFFFFFFFF, model.face(FaceDir.UP).getRGB(0, 0));
    }

    @Test
    void differentTintsProduceDifferentPixels() {
        BlockModel model = sampleModel(0xFFFFFFFF);
        BlockModel a = model.withTint(0xFF71A74D);
        BlockModel b = model.withTint(0xFF59AE30);
        assertTrue(a.face(FaceDir.UP).getRGB(0, 0) != b.face(FaceDir.UP).getRGB(0, 0));
        assertNotEquals(a.face(FaceDir.UP).getRGB(0, 0), b.face(FaceDir.UP).getRGB(0, 0));
    }

    private static BlockModel sampleModel(int srcArgb) {
        EnumMap<FaceDir, BufferedImage> faces = new EnumMap<>(FaceDir.class);
        for (FaceDir d : FaceDir.values()) {
            BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) img.setRGB(x, y, srcArgb);
            faces.put(d, img);
        }
        return new BlockModel(BlockKey.of("minecraft:oak_leaves"), faces, true,
                "minecraft:block/leaves", Map.of());
    }
}
