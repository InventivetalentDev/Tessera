package io.tessera.assets.model;

import java.awt.image.BufferedImage;

/**
 * Per-pixel multiply of a source PNG by an ARGB tint, mirroring how vanilla
 * Minecraft applies biome colormap output at render time. Alpha is preserved
 * from the source; RGB channels are multiplied (in [0,255] integer space) by
 * the tint's RGB channels, divided by 255.
 *
 * <p>v1 applies one tint to all six face PNGs of a {@link BlockModel} —
 * see {@link BlockModel#withTint}. The cube-parent abstraction in
 * {@link ModelResolver} hides per-face {@code tintindex} info, so blocks like
 * grass_block (top tinted, dirt sides not) get tinted on every face. This is
 * acceptable for the brief break animation and a per-face refinement is
 * deferred.
 */
final class TintApplier {

    private TintApplier() {}

    static BufferedImage multiply(BufferedImage src, int tintArgb) {
        int tr = (tintArgb >> 16) & 0xFF;
        int tg = (tintArgb >> 8) & 0xFF;
        int tb = tintArgb & 0xFF;
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = ((argb >> 16) & 0xFF) * tr / 255;
                int g = ((argb >> 8) & 0xFF) * tg / 255;
                int b = (argb & 0xFF) * tb / 255;
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }
}
