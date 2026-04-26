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
        BufferedImage padded = padToFaceSize(tile, face.width(), face.height());

        if (face == HeadFace.BOTTOM) {
            // Mirror horizontally on paint: dx1/dx2 swap = source U-axis flips.
            g.drawImage(padded,
                    face.u1, face.v0, face.u0, face.v1,
                    0, 0, padded.getWidth(), padded.getHeight(),
                    null);
        } else {
            g.drawImage(padded, face.u0, face.v0, null);
        }
    }

    private static BufferedImage padToFaceSize(BufferedImage tile, int targetW, int targetH) {
        int tw = tile.getWidth();
        int th = tile.getHeight();
        if (tw == targetW && th == targetH) return tile;

        BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        int padX = (targetW - tw) / 2;
        int padY = (targetH - th) / 2;
        // Paint the tile centered. Outside the tile, replicate the nearest edge pixel
        // so 8x8 sampling at the seam reads colors from the same chunk rather than
        // transparent (which would render see-through on the head).
        for (int y = 0; y < targetH; y++) {
            int sy = clamp(y - padY, 0, th - 1);
            for (int x = 0; x < targetW; x++) {
                int sx = clamp(x - padX, 0, tw - 1);
                out.setRGB(x, y, tile.getRGB(sx, sy));
            }
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
