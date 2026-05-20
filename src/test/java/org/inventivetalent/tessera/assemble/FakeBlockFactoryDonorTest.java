package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.FaceDir;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
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

    private static HeadsRegistry.ChunkEntry chunk(String hash, FaceDir... outward) {
        HeadsRegistry.Entry skin = new HeadsRegistry.Entry(hash, "value-" + hash, "sig-" + hash, null);
        EnumSet<FaceDir> set = outward.length == 0
                ? EnumSet.noneOf(FaceDir.class)
                : EnumSet.copyOf(java.util.Arrays.asList(outward));
        return new HeadsRegistry.ChunkEntry(skin, set);
    }

    @Test
    void prefersFaceCenterOverCornersAndEdges() {
        int gridN = 4;
        Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks = new LinkedHashMap<>();
        HeadsRegistry.ChunkEntry corner = chunk("corner", FaceDir.DOWN, FaceDir.NORTH, FaceDir.WEST);
        HeadsRegistry.ChunkEntry edge = chunk("edge", FaceDir.DOWN, FaceDir.NORTH);
        HeadsRegistry.ChunkEntry faceCenter = chunk("face-center", FaceDir.NORTH);
        chunks.put(new ChunkCoord(0, 0, 0), corner);
        chunks.put(new ChunkCoord(0, 0, 1), edge);
        chunks.put(new ChunkCoord(1, 1, 0), faceCenter);
        chunks.put(new ChunkCoord(3, 3, 3), corner);

        assertSame(faceCenter, FakeBlockFactory.pickInteriorDonor(chunks, gridN));
    }

    @Test
    void fallsBackToFirstWhenNoFaceCenterAvailable() {
        // Every chunk is multi-outward — picker returns the first iterated value.
        int gridN = 2;
        Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks = new LinkedHashMap<>();
        HeadsRegistry.ChunkEntry first = chunk("first", FaceDir.DOWN, FaceDir.NORTH, FaceDir.WEST);
        HeadsRegistry.ChunkEntry second = chunk("second", FaceDir.UP, FaceDir.SOUTH, FaceDir.EAST);
        chunks.put(new ChunkCoord(0, 0, 0), first);
        chunks.put(new ChunkCoord(1, 1, 1), second);

        assertSame(first, FakeBlockFactory.pickInteriorDonor(chunks, gridN));
    }

    @Test
    void returnsNullForEmptyChunkMap() {
        assertNull(FakeBlockFactory.pickInteriorDonor(Map.of(), 4));
    }

    @Test
    void picksFaceCenterFromSparseMapWithSingleEntry() {
        int gridN = 4;
        Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks = new LinkedHashMap<>();
        HeadsRegistry.ChunkEntry only = chunk("only", FaceDir.NORTH);
        chunks.put(new ChunkCoord(2, 1, 0), only);

        HeadsRegistry.ChunkEntry picked = FakeBlockFactory.pickInteriorDonor(chunks, gridN);
        assertNotNull(picked);
        assertSame(only, picked);
    }
}
