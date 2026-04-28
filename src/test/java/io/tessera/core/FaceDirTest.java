package io.tessera.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceDirTest {

    @Test
    void normalsMatchAxisConvention() {
        assertEquals(0, FaceDir.UP.dx);
        assertEquals(1, FaceDir.UP.dy);
        assertEquals(1, FaceDir.EAST.dx);
        assertEquals(-1, FaceDir.WEST.dx);
        assertEquals(1, FaceDir.SOUTH.dz);
        assertEquals(-1, FaceDir.NORTH.dz);
    }

    @Test
    void isOutwardAtMatchesGridBoundaries() {
        int gridN = 4;
        int last = gridN - 1;

        assertTrue(FaceDir.DOWN.isOutwardAt(2, 0, 2, gridN));
        assertFalse(FaceDir.DOWN.isOutwardAt(2, 1, 2, gridN));

        assertTrue(FaceDir.UP.isOutwardAt(0, last, 0, gridN));
        assertFalse(FaceDir.UP.isOutwardAt(0, last - 1, 0, gridN));

        assertTrue(FaceDir.NORTH.isOutwardAt(1, 1, 0, gridN));
        assertTrue(FaceDir.SOUTH.isOutwardAt(1, 1, last, gridN));
        assertTrue(FaceDir.WEST.isOutwardAt(0, 1, 1, gridN));
        assertTrue(FaceDir.EAST.isOutwardAt(last, 1, 1, gridN));
    }

    @Test
    void shadeMatchesVanilla() {
        assertEquals(1.0f, FaceDir.UP.shade());
        assertEquals(0.5f, FaceDir.DOWN.shade());
        assertEquals(0.8f, FaceDir.NORTH.shade());
        assertEquals(0.8f, FaceDir.SOUTH.shade());
        assertEquals(0.6f, FaceDir.WEST.shade());
        assertEquals(0.6f, FaceDir.EAST.shade());
    }

    @Test
    void jsonNameRoundTrips() {
        for (FaceDir d : FaceDir.values()) {
            assertEquals(d, FaceDir.fromJson(d.jsonName()));
        }
    }
}
