package io.tessera.core;

/**
 * Position of a sub-block chunk in the N×N×N split grid. Coordinates are
 * 0-indexed; {@code (0,0,0)} is the corner where every {@link FaceDir} that
 * has a -1 component is outward (i.e. the (DOWN, NORTH, WEST) corner).
 *
 * <p>Encoded as a stable string {@code "x,y,z"} in heads.json so per-chunk
 * skin overrides can be looked up without an external schema.
 */
public record ChunkCoord(int x, int y, int z) implements Comparable<ChunkCoord> {

    public ChunkCoord {
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException(
                    "ChunkCoord components must be non-negative: " + x + "," + y + "," + z);
        }
    }

    public String asKey() {
        return x + "," + y + "," + z;
    }

    public static ChunkCoord parseKey(String s) {
        String[] parts = s.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("ChunkCoord key must be 'x,y,z': " + s);
        }
        return new ChunkCoord(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
    }

    @Override
    public int compareTo(ChunkCoord o) {
        int c = Integer.compare(y, o.y);
        if (c != 0) return c;
        c = Integer.compare(z, o.z);
        if (c != 0) return c;
        return Integer.compare(x, o.x);
    }
}
