package io.tessera.skin;

import io.tessera.core.HeadFace;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Paints up to 6 chunk tiles onto a 64x64 transparent skin canvas. Mosaikin
 * port — the BOTTOM-flip is the critical bit and is preserved verbatim.
 *
 * <p><b>BOTTOM face quirk:</b> vanilla Minecraft samples the head's BOTTOM
 * face with its U axis flipped relative to all other faces. If we painted
 * the chunk normally into the BOTTOM UV rect, the rendered face would show
 * the image mirrored horizontally. To compensate, BOTTOM tiles are
 * pre-mirrored on paint via the 9-arg {@code drawImage} with swapped
 * destination {@code dx1}/{@code dx2}. The vanilla renderer's flip then
 * cancels our flip, leaving the image right-side-up on the cube.
 *
 * <p>Tile-to-region paste rule: a chunk's tile (typically 4x4 px when
 * {@code gridN=4}) is center-pasted into the 8x8 region. Edge pixels are
 * padded outward to fill the full 8x8 so that any 8x8 mip-mapping or
 * texture-filter sampling produces consistent colors at the seam between
 * chunk heads. (For {@code gridN=2}, tile = 8x8, no padding needed.)
 */
public final class SkinAssembler {

    /**
     * Paint the chunks of {@code head} onto a 64x64 PNG and write it to
     * {@code outDir}. Returns the file path. Sets {@code head.pngFile} to
     * {@code <outDirName>/<head.id>.png} so the upload step can find it.
     */
    public Path assemble(HeadSkin head, Path outDir) throws IOException {
        Files.createDirectories(outDir);

        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = skin.createGraphics();
        try {
            g.setComposite(AlphaComposite.Src);
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, 64, 64);

            for (Map.Entry<HeadFace, BufferedImage> entry : head.tiles().entrySet()) {
                paintFace(g, entry.getKey(), entry.getValue());
            }
        } finally {
            g.dispose();
        }

        Path out = outDir.resolve(head.id() + ".png");
        ImageIO.write(skin, "PNG", out.toFile());
        head.pngFile(outDir.getFileName() + "/" + out.getFileName());
        return out;
    }

    private void paintFace(Graphics2D g, HeadFace face, BufferedImage tile) {
        if (tile == null) return;
        // Per-face in-plane rotation (multiple of 90°) — applied to the tile
        // before scaling/painting so the resulting image lands in the slot
        // with its image axes aligned to the slot's UV axes. See TileRotations
        // for the empirical-tuning rationale.
        BufferedImage rotated = rotate90Multiples(tile, TileRotations.of(face));
        BufferedImage scaled = nearestNeighborScale(rotated, face.width(), face.height());

        if (face == HeadFace.BOTTOM) {
            // Mirror horizontally on paint: dx1/dx2 swap = source U-axis flips.
            g.drawImage(scaled,
                    face.u1, face.v0, face.u0, face.v1,
                    0, 0, scaled.getWidth(), scaled.getHeight(),
                    null);
        } else {
            g.drawImage(scaled, face.u0, face.v0, null);
        }
    }

    private static BufferedImage rotate90Multiples(BufferedImage src, int degrees) {
        int turns = (((degrees % 360) + 360) % 360) / 90;
        if (turns == 0) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        // Output size flips for 90/270, stays for 180.
        int outW = (turns % 2 == 1) ? h : w;
        int outH = (turns % 2 == 1) ? w : h;
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = src.getRGB(x, y);
                int nx, ny;
                switch (turns) {
                    case 1 -> { nx = h - 1 - y; ny = x; }              // 90° CW
                    case 2 -> { nx = w - 1 - x; ny = h - 1 - y; }      // 180°
                    case 3 -> { nx = y;         ny = w - 1 - x; }      // 270° CW (= 90° CCW)
                    default -> { nx = x; ny = y; }
                }
                out.setRGB(nx, ny, rgb);
            }
        }
        return out;
    }

    /**
     * NEAREST-neighbor upscale of {@code src} to {@code targetW × targetH}.
     * For typical Tessera dimensions (4×4 tile → 8×8 head face slot) each
     * source pixel becomes a clean 2×2 block in the output, so the slot is
     * fully covered with no stretched edges.
     *
     * <p>An earlier version of this method center-pasted the tile and
     * replicated edge pixels outward — i.e. for a 4×4 tile in an 8×8 slot,
     * the inner 2×2 of the slot showed "real" tile pixels (cols 1–2 of the
     * tile) while the outer 6 pixels of each row were 3-wide replications
     * of the tile's leftmost/rightmost columns. That produced a visible
     * "2×2 in the centre, stretched edges" pattern on every rendered face —
     * which masked rotation effects and made textures look broken.
     */
    private static BufferedImage nearestNeighborScale(BufferedImage src, int targetW, int targetH) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        if (sw == targetW && sh == targetH) return src;

        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < targetH; y++) {
            int sy = (y * sh) / targetH;
            for (int x = 0; x < targetW; x++) {
                int sx = (x * sw) / targetW;
                out.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return out;
    }
}
