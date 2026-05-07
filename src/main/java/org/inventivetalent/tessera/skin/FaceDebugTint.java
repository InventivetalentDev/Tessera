package org.inventivetalent.tessera.skin;

import org.inventivetalent.tessera.core.HeadFace;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Replaces a chunk tile with a high-contrast directional marker before the
 * SkinAssembler paints it, so each rendered face slot is visually identifiable
 * in-game. Crucial for diagnosing wrap mismatches and rotation issues —
 * with real block textures it can be impossible to tell which face a
 * particular pixel is actually showing, but with the debug pattern you can
 * read it off directly.
 *
 * <p>Pattern per face:
 * <ul>
 *   <li>1-pixel border on each edge in a face-specific color, with the four
 *       sides distinct: <b>top edge red, right green, bottom blue, left yellow</b>.
 *       That fixes orientation: if you see a red strip at the bottom of a
 *       rendered face, you know that face is rendered upside-down in the cube.</li>
 *   <li>Face letter (T/B/F/K/L/R) drawn near the center, in a face-specific
 *       fill color, so you also know which slot you're looking at without
 *       having to derive it from neighbouring faces.</li>
 * </ul>
 *
 * <p>Bake-time only — toggling on/off requires re-baking. Invalidates the
 * registry so the next test re-bakes with (or without) the markers.
 */
public final class FaceDebugTint {

    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    private FaceDebugTint() {}

    public static boolean isEnabled() { return ENABLED.get(); }

    public static void setEnabled(boolean v) {
        boolean prev = ENABLED.getAndSet(v);
        if (prev != v) TileRotations.markStale();
    }

    /**
     * Build a {@code size×size} debug marker for the given HeadFace. Called
     * from {@link SkinAssembler} when debug-tint mode is on, immediately
     * before scaling onto the head's UV slot.
     */
    public static BufferedImage marker(HeadFace face, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int fill = fillColorFor(face);
        // Solid background so the rest of the slot picks up the face color
        // even if scaling skips some center pixels.
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                out.setRGB(x, y, fill);
            }
        }
        // Edge stripes — top, right, bottom, left — distinct colors so the
        // viewer can read off orientation directly. Stripe width scales
        // with size so it survives the nearest-neighbor upscale that
        // SkinAssembler does next.
        int stripe = Math.max(1, size / 4);
        int top    = 0xFFE53935; // red
        int right  = 0xFF43A047; // green
        int bottom = 0xFF1E88E5; // blue
        int left   = 0xFFFDD835; // yellow
        for (int y = 0; y < stripe; y++)
            for (int x = 0; x < size; x++) out.setRGB(x, y, top);
        for (int y = size - stripe; y < size; y++)
            for (int x = 0; x < size; x++) out.setRGB(x, y, bottom);
        for (int x = 0; x < stripe; x++)
            for (int y = 0; y < size; y++) out.setRGB(x, y, left);
        for (int x = size - stripe; x < size; x++)
            for (int y = 0; y < size; y++) out.setRGB(x, y, right);
        return out;
    }

    /** Single-letter face label color used by {@link #marker} - kept distinct from edge colors. */
    private static int fillColorFor(HeadFace face) {
        return switch (face) {
            case TOP    -> 0xFFEEEEEE; // light gray
            case BOTTOM -> 0xFF424242; // dark gray
            case FRONT  -> 0xFFFFA000; // orange
            case BACK   -> 0xFF8E24AA; // purple
            case LEFT   -> 0xFF00ACC1; // teal
            case RIGHT  -> 0xFFC2185B; // magenta
        };
    }
}
