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
 * port — but the BOTTOM U-pre-mirror that came over from Mosaikin has been
 * removed; see history below.
 *
 * <p><b>BOTTOM face note:</b> vanilla block rendering samples the BOTTOM
 * face with U flipped, but the player-head model's BOTTOM UV (used by
 * ItemDisplay rendering) does <em>not</em> apply the same flip. An earlier
 * version of this class pre-mirrored BOTTOM tiles on paint to compensate
 * for the assumed flip — that was wrong, and produced an X-flipped BOTTOM
 * face that was invisible on V/H-symmetric textures (oak_planks, stone)
 * but mirrored asymmetric ones (oak_log_top rings). Now BOTTOM paints
 * identically to the other faces.
 *
 * <p>Tile-to-region paste rule: a chunk's tile (typically 4x4 px when
 * {@code gridN=4}) is upscaled with nearest-neighbor sampling to the 8x8
 * slot region by {@link #nearestNeighborScale}.
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
        // Debug-tint mode replaces the tile with a directional marker so
        // wrap mismatches are visible at a glance. Applied BEFORE rotation
        // and flip so the rotation/flip knobs still produce visibly
        // different output (otherwise you'd be staring at the same colored
        // borders with no way to tell what they did).
        BufferedImage source = FaceDebugTint.isEnabled()
                ? FaceDebugTint.marker(face, tile.getWidth())
                : tile;
        // Per-face in-plane rotation + optional mirror — applied to the tile
        // before scaling/painting so the resulting image lands in the slot
        // with its image axes aligned to the slot's UV axes. The slot's
        // axes don't always match the source face's axes by a pure rotation
        // (the head's TOP slot vs. a block's UP face is a known case);
        // TileFlips covers the other half of the dihedral group.
        BufferedImage rotated = rotate90Multiples(source, TileRotations.of(face));
        BufferedImage flipped = applyFlip(rotated, TileFlips.of(face));
        BufferedImage scaled = nearestNeighborScale(flipped, face.width(), face.height());

        g.drawImage(scaled, face.u0, face.v0, null);
    }

    /** Apply horizontal/vertical/both mirror per {@link TileFlips}. */
    private static BufferedImage applyFlip(BufferedImage src, TileFlips.Flip flip) {
        if (flip == TileFlips.Flip.NONE) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            int sy = (flip == TileFlips.Flip.V || flip == TileFlips.Flip.HV) ? (h - 1 - y) : y;
            for (int x = 0; x < w; x++) {
                int sx = (flip == TileFlips.Flip.H || flip == TileFlips.Flip.HV) ? (w - 1 - x) : x;
                out.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return out;
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
