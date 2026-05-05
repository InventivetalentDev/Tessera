package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link FakeBlockFactory#pickInteriorDonor} chooses the texture used to
 * paint interior chunks when {@code animation.fillInterior} is enabled.
 * Face-center chunks are strictly preferred because their HeadSkinPacker
 * filler-tile fallback replicates the visible face texture across all six
 * head-skin slots — using one for an interior chunk renders the block's
 * surface texture from any viewing angle for the brief moment of exposure.
 */
class FakeBlockFactoryDonorTest {

    private static HeadsRegistry.Entry entry(String hash) {
        return new HeadsRegistry.Entry(hash, "value-" + hash, "sig-" + hash, null);
    }

    @Test
    void prefersFaceCenterOverCornersAndEdges() {
        // gridN=4, last=3. Mix corners, edges, and one face-center; the
        // face-center should win regardless of map order.
        int gridN = 4;
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = new LinkedHashMap<>();
        HeadsRegistry.Entry corner = entry("corner");
        HeadsRegistry.Entry edge = entry("edge");
        HeadsRegistry.Entry faceCenter = entry("face-center");
        chunks.put(new ChunkCoord(0, 0, 0), corner);          // 3 outward → corner
        chunks.put(new ChunkCoord(0, 0, 1), edge);            // 2 outward → edge
        chunks.put(new ChunkCoord(1, 1, 0), faceCenter);      // 1 outward → face-center
        chunks.put(new ChunkCoord(3, 3, 3), corner);          // another corner

        assertSame(faceCenter, FakeBlockFactory.pickInteriorDonor(chunks, gridN));
    }

    @Test
    void fallsBackToFirstWhenNoFaceCenterAvailable() {
        // Pathological grid (gridN=2 has no face-center chunks at all -
        // every chunk is at a boundary on every axis) - any chunk is fine,
        // pickInteriorDonor returns the first iterated value.
        int gridN = 2;
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = new LinkedHashMap<>();
        HeadsRegistry.Entry first = entry("first");
        HeadsRegistry.Entry second = entry("second");
        chunks.put(new ChunkCoord(0, 0, 0), first);
        chunks.put(new ChunkCoord(1, 1, 1), second);

        assertSame(first, FakeBlockFactory.pickInteriorDonor(chunks, gridN));
    }

    @Test
    void returnsNullForEmptyChunkMap() {
        // Defensive: callers gate on chunks.isEmpty() before invoking, but
        // the donor picker shouldn't NPE if it slips through.
        assertNull(FakeBlockFactory.pickInteriorDonor(Map.of(), 4));
    }

    @Test
    void picksFaceCenterFromSparseMapWithSingleEntry() {
        // Single face-center in the map should be returned even when iterated
        // first — this exercises the "found a face-center" early return.
        int gridN = 4;
        Map<ChunkCoord, HeadsRegistry.Entry> chunks = new LinkedHashMap<>();
        HeadsRegistry.Entry only = entry("only");
        chunks.put(new ChunkCoord(2, 1, 0), only);  // 1 outward (NORTH) → face-center

        HeadsRegistry.Entry picked = FakeBlockFactory.pickInteriorDonor(chunks, gridN);
        assertNotNull(picked);
        assertSame(only, picked);
    }
}
