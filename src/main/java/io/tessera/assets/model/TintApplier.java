package io.tessera.assets.model;

import java.awt.image.BufferedImage;

/**
 * Pixel operations for biome tinting: multiply and alpha-composite.
 *
 * <p>{@link #multiply} mirrors how vanilla applies biome colormap output —
 * channel-wise multiply, alpha preserved.
 *
 * <p>{@link #composite} handles grass_block's two-element model: the untinted
 * dirt side base + the semi-transparent tinted overlay are baked together
 * at skin-paint time so the FakeBlock renders correctly without needing
 * a second entity layer.
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

    /**
     * Alpha-composite {@code overlay} on top of {@code base} (source-over).
     * {@code base} is assumed fully opaque; output alpha is always 0xFF.
     */
    static BufferedImage composite(BufferedImage base, BufferedImage overlay) {
        int w = base.getWidth();
        int h = base.getHeight();
        int ow = overlay.getWidth();
        int oh = overlay.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int b = base.getRGB(x, y);
                int o = (x < ow && y < oh) ? overlay.getRGB(x, y) : 0;
                int oa = (o >>> 24) & 0xFF;
                int pixel;
                if (oa == 0) {
                    pixel = b | 0xFF000000;
                } else if (oa == 255) {
                    pixel = o | 0xFF000000;
                } else {
                    float a = oa / 255f;
                    int r  = (int)(a * ((o >> 16) & 0xFF) + (1 - a) * ((b >> 16) & 0xFF));
                    int g  = (int)(a * ((o >> 8)  & 0xFF) + (1 - a) * ((b >> 8)  & 0xFF));
                    int bl = (int)(a * (o & 0xFF)          + (1 - a) * (b & 0xFF));
                    pixel = 0xFF000000 | (r << 16) | (g << 8) | bl;
                }
                out.setRGB(x, y, pixel);
            }
        }
        return out;
    }
}
