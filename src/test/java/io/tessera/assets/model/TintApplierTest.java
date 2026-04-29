package io.tessera.assets.model;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TintApplierTest {

    @Test
    void multiplyByOpaqueWhiteIsIdentity() {
        BufferedImage src = solid(0xFF80C040);
        BufferedImage out = TintApplier.multiply(src, 0xFFFFFFFF);
        assertEquals(0xFF80C040, out.getRGB(0, 0));
    }

    @Test
    void multiplyScalesEachChannelIndependently() {
        // Source white (0xFFFFFFFF) multiplied by a tint should yield the
        // tint's RGB with full alpha — the standard biome-colormap pattern.
        BufferedImage src = solid(0xFFFFFFFF);
        BufferedImage out = TintApplier.multiply(src, 0xFF71A74D);
        assertEquals(0xFF71A74D, out.getRGB(0, 0));
    }

    @Test
    void multiplyPreservesSourceAlpha() {
        // 50%-transparent source pixel; tint has full alpha. Result alpha
        // must come from the source (translucent leaves shouldn't become
        // opaque after tinting).
        BufferedImage src = solid(0x80FFFFFF);
        BufferedImage out = TintApplier.multiply(src, 0xFF808080);
        assertEquals(0x80, (out.getRGB(0, 0) >>> 24) & 0xFF);
    }

    @Test
    void multiplyByHalfGrayHalvesEachChannel() {
        // 0x80 / 0xFF * 0xFF == 0x80 (integer math). Verifies the divide-by-255
        // formula doesn't drift on a clean midpoint.
        BufferedImage src = solid(0xFFFFFFFF);
        BufferedImage out = TintApplier.multiply(src, 0xFF808080);
        int rgb = out.getRGB(0, 0);
        assertEquals(0x80, (rgb >> 16) & 0xFF);
        assertEquals(0x80, (rgb >> 8) & 0xFF);
        assertEquals(0x80, rgb & 0xFF);
    }

    @Test
    void multiplyReturnsFreshImage() {
        BufferedImage src = solid(0xFFFFFFFF);
        BufferedImage out = TintApplier.multiply(src, 0xFF808080);
        assertNotSame(src, out);
        // Source untouched.
        assertEquals(0xFFFFFFFF, src.getRGB(0, 0));
    }

    private static BufferedImage solid(int argb) {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setRGB(x, y, argb);
            }
        }
        return img;
    }
}
