package org.inventivetalent.tessera.assets.model;

import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.FaceDir;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockModelWithTintTest {

    @Test
    void withZeroTintReturnsSameInstance() {
        BlockModel model = allTinted(0xFFFFFFFF);
        assertSame(model, model.withTint(0));
    }

    @Test
    void withNonZeroTintReturnsCopy() {
        BlockModel model = allTinted(0xFFFFFFFF);
        assertNotSame(model, model.withTint(0xFF71A74D));
    }

    @Test
    void tintedFaceIsMultiplied() {
        // oak_leaves: all 6 faces tinted → all should pick up the biome color.
        BlockModel tinted = allTinted(0xFFFFFFFF).withTint(0xFF71A74D);
        for (FaceDir d : FaceDir.values()) {
            assertEquals(0xFF71A74D, tinted.face(d).getRGB(0, 0),
                    "tint not applied to face " + d);
        }
    }

    @Test
    void untintedFaceIsUnchanged() {
        // Simulate grass_block: only UP is tinted; sides are untinted.
        BlockModel model = perFaceTinted(0xFFFFFFFF, EnumSet.of(FaceDir.UP));
        BlockModel tinted = model.withTint(0xFF71A74D);
        // UP gets tinted
        assertEquals(0xFF71A74D, tinted.face(FaceDir.UP).getRGB(0, 0));
        // All other faces stay white
        for (FaceDir d : FaceDir.values()) {
            if (d == FaceDir.UP) continue;
            assertEquals(0xFFFFFFFF, tinted.face(d).getRGB(0, 0),
                    "untinted face " + d + " should not change");
        }
    }

    @Test
    void overlayIsCompositedOntoBase() {
        // A mostly-transparent green overlay on top of a white base should
        // produce a pixel between pure green and white, proportional to alpha.
        EnumMap<FaceDir, BufferedImage> bases = solidFaces(0xFFFFFFFF);  // white base
        EnumMap<FaceDir, BufferedImage> overlays = new EnumMap<>(FaceDir.class);
        // 50%-transparent red overlay on NORTH only
        BufferedImage ovl = solid(0x80FF0000, 2, 2);
        overlays.put(FaceDir.NORTH, ovl);

        BlockModel model = new BlockModel(
                BlockKey.of("minecraft:grass_block"), bases,
                EnumSet.noneOf(FaceDir.class), overlays,
                "block/block", Map.of());

        // Tint is pure white (no-op multiply) so composite uses overlay as-is.
        BlockModel result = model.withTint(0xFFFFFFFF);

        // NORTH: 50% red on white → R=255, G=127, B=127
        int north = result.face(FaceDir.NORTH).getRGB(0, 0);
        assertEquals(255, (north >> 16) & 0xFF, "R should be 255");
        int g = (north >> 8) & 0xFF;
        assertTrue(g > 100 && g < 150, "G should be ~127, got " + g);

        // SOUTH should be unchanged (no overlay there)
        assertEquals(0xFFFFFFFF, result.face(FaceDir.SOUTH).getRGB(0, 0));
    }

    @Test
    void withTintPreservesMetadata() {
        BlockModel model = allTinted(0xFFFFFFFF);
        BlockModel tinted = model.withTint(0xFF808080);
        assertEquals(model.key(), tinted.key());
        assertEquals(model.tinted(), tinted.tinted());
        assertEquals(model.parentChain(), tinted.parentChain());
        assertEquals(model.variantRotations(), tinted.variantRotations());
    }

    @Test
    void withTintLeavesSourceFacesUntouched() {
        BlockModel model = allTinted(0xFFFFFFFF);
        model.withTint(0xFF71A74D);
        assertEquals(0xFFFFFFFF, model.face(FaceDir.UP).getRGB(0, 0));
    }

    @Test
    void differentTintsProduceDifferentPixels() {
        BlockModel model = allTinted(0xFFFFFFFF);
        BlockModel a = model.withTint(0xFF71A74D);
        BlockModel b = model.withTint(0xFF59AE30);
        assertNotEquals(a.face(FaceDir.UP).getRGB(0, 0), b.face(FaceDir.UP).getRGB(0, 0));
    }

    // --- helpers ---

    private static BlockModel allTinted(int argb) {
        return new BlockModel(BlockKey.of("minecraft:oak_leaves"), solidFaces(argb),
                true, "minecraft:block/leaves", Map.of());
    }

    private static BlockModel perFaceTinted(int argb, EnumSet<FaceDir> tinted) {
        return new BlockModel(BlockKey.of("minecraft:grass_block"), solidFaces(argb),
                tinted, Collections.emptyMap(), "block/block", Map.of());
    }

    private static EnumMap<FaceDir, BufferedImage> solidFaces(int argb) {
        EnumMap<FaceDir, BufferedImage> faces = new EnumMap<>(FaceDir.class);
        for (FaceDir d : FaceDir.values()) faces.put(d, solid(argb, 2, 2));
        return faces;
    }

    private static BufferedImage solid(int argb, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        return img;
    }
}
