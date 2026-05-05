package org.inventivetalent.tessera.core;

import org.joml.Vector3i;

/**
 * One of the six block-local face directions. Distinct from {@link HeadFace},
 * which is the player-head's UV layout — the FaceDir → HeadFace mapping is
 * what the assemble layer's right-rotation table picks.
 *
 * <p>Axis convention matches Minecraft block-space: +X = east, +Y = up,
 * +Z = south. {@link #DOWN} corresponds to NBT {@code "down"}, etc.
 */
public enum FaceDir {
    DOWN ( 0, -1,  0),
    UP   ( 0,  1,  0),
    NORTH( 0,  0, -1),
    SOUTH( 0,  0,  1),
    WEST (-1,  0,  0),
    EAST ( 1,  0,  0);

    public final int dx, dy, dz;

    FaceDir(int dx, int dy, int dz) {
        this.dx = dx; this.dy = dy; this.dz = dz;
    }

    public Vector3i normal() {
        return new Vector3i(dx, dy, dz);
    }

    public boolean isOutwardAt(int x, int y, int z, int gridN) {
        int last = gridN - 1;
        return switch (this) {
            case DOWN  -> y == 0;
            case UP    -> y == last;
            case NORTH -> z == 0;
            case SOUTH -> z == last;
            case WEST  -> x == 0;
            case EAST  -> x == last;
        };
    }

    /**
     * Vanilla block-face directional shading factor, sourced from
     * {@code ClientLevel.getShade()} in the decompiled 1.21.4 client.
     * Applied to quads whose {@code BakedQuad.isShade()} is true (all
     * standard cube blocks). Nether values (all faces 0.9) are omitted;
     * Tessera targets Overworld only in v1.
     */
    public float shade() {
        return switch (this) {
            case DOWN          -> 0.5f;
            case UP            -> 1.0f;
            case NORTH, SOUTH  -> 0.8f;
            case WEST, EAST    -> 0.6f;
        };
    }

    /** Lower-case JSON name as used in vanilla block model {@code "faces"} maps. */
    public String jsonName() {
        return name().toLowerCase();
    }

    public static FaceDir fromJson(String s) {
        return valueOf(s.toUpperCase());
    }
}
