package io.tessera.assemble;

import io.tessera.core.ChunkCoord;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockGeometryTest {

    private static final float EPS = 1e-6f;

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16})
    void chunkScaleIs2OverGridN(int gridN) {
        // Player-head ItemDisplay renders an 8-model-unit cube (= 0.5 blocks
        // at scale 1.0); a chunk needs 1/N blocks per axis, so the per-chunk
        // scale must be 2/N. If this diverges from the geometry math elsewhere,
        // adjacent chunks visibly stop tiling.
        assertEquals(2f / gridN, BlockGeometry.axisAligned(gridN).chunkScale(), EPS);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4, 8, 16})
    void chunkCentersTileTheUnitCube(int gridN) {
        BlockGeometry geom = BlockGeometry.axisAligned(gridN);
        Vector3f first = geom.chunkLocalCenter(new ChunkCoord(0, 0, 0));
        Vector3f last = geom.chunkLocalCenter(new ChunkCoord(gridN - 1, gridN - 1, gridN - 1));
        // First-cell center sits 1/(2N) inside the origin face; last-cell
        // center sits 1/(2N) inside the far face. Together they bracket the
        // unit cube exactly so the lattice neither overhangs nor leaves gaps.
        assertEquals(0.5f / gridN, first.x, EPS);
        assertEquals(0.5f / gridN, first.y, EPS);
        assertEquals(0.5f / gridN, first.z, EPS);
        assertEquals(1f - 0.5f / gridN, last.x, EPS);
        assertEquals(1f - 0.5f / gridN, last.y, EPS);
        assertEquals(1f - 0.5f / gridN, last.z, EPS);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4, 8, 16})
    void adjacentChunkCentersAreOneOverGridNApart(int gridN) {
        BlockGeometry geom = BlockGeometry.axisAligned(gridN);
        Vector3f a = geom.chunkLocalCenter(new ChunkCoord(0, 0, 0));
        Vector3f b = geom.chunkLocalCenter(new ChunkCoord(1, 0, 0));
        assertEquals(1f / gridN, b.x - a.x, EPS);
    }

    @Test
    void rejectsZeroOrNegativeGridN() {
        assertThrows(IllegalArgumentException.class, () -> new BlockGeometry(0, new Quaternionf()));
        assertThrows(IllegalArgumentException.class, () -> new BlockGeometry(-1, new Quaternionf()));
    }
}
