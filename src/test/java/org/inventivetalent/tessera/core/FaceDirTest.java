package org.inventivetalent.tessera.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    /**
     * Number of cells in an N×N×N chunk grid that have at least one outward
     * face (i.e. would actually spawn an ItemDisplay). For N≥2 the count is
     * 6N²−12N+8 (8 corners + 12(N−2) edges + 6(N−2)² face-centers); N=1
     * collapses to a single cell that is outward on all six faces. The
     * lattice size depends on this directly — a regression here would
     * silently change the per-block entity count.
     */
    @ParameterizedTest
    @CsvSource({
            "1,    1",
            "2,    8",
            "4,   56",
            "8,  296",
            "16, 1352",
    })
    void visibleChunkCountMatchesFormula(int gridN, int expected) {
        int count = 0;
        for (int x = 0; x < gridN; x++) {
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    for (FaceDir d : FaceDir.values()) {
                        if (d.isOutwardAt(x, y, z, gridN)) {
                            count++;
                            break;
                        }
                    }
                }
            }
        }
        assertEquals(expected, count);
    }

    @Test
    void singleChunkAtGridN1IsOutwardOnAllSixFaces() {
        for (FaceDir d : FaceDir.values()) {
            assertTrue(d.isOutwardAt(0, 0, 0, 1), d + " should be outward at gridN=1");
        }
    }

    @Test
    void interiorCellsAtGridN4HaveNoOutwardFace() {
        for (int x = 1; x <= 2; x++) {
            for (int y = 1; y <= 2; y++) {
                for (int z = 1; z <= 2; z++) {
                    for (FaceDir d : FaceDir.values()) {
                        assertFalse(d.isOutwardAt(x, y, z, 4),
                                d + " unexpectedly outward at (" + x + "," + y + "," + z + ")");
                    }
                }
            }
        }
    }

    @Test
    void cornerCellAtGridN16IsOutwardOnExactlyThreeFaces() {
        int last = 15;
        int outward = 0;
        for (FaceDir d : FaceDir.values()) {
            if (d.isOutwardAt(last, last, last, 16)) outward++;
        }
        assertEquals(3, outward);
    }
}
