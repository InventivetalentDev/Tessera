package org.inventivetalent.tessera.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkCoordTest {

    @Test
    void asKeyAndParseKeyRoundTrip() {
        ChunkCoord c = new ChunkCoord(1, 2, 3);
        assertEquals("1,2,3", c.asKey());
        assertEquals(c, ChunkCoord.parseKey(c.asKey()));
    }

    @Test
    void parseKeyRejectsMalformed() {
        assertThrows(IllegalArgumentException.class, () -> ChunkCoord.parseKey("1,2"));
        assertThrows(NumberFormatException.class, () -> ChunkCoord.parseKey("a,b,c"));
    }

    @Test
    void rejectsNegativeComponents() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkCoord(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkCoord(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkCoord(0, 0, -1));
    }

    @Test
    void compareToOrdersByYThenZThenX() {
        List<ChunkCoord> coords = new ArrayList<>(List.of(
                new ChunkCoord(1, 0, 0),
                new ChunkCoord(0, 1, 0),
                new ChunkCoord(0, 0, 1),
                new ChunkCoord(0, 0, 0)));
        Collections.sort(coords);
        assertEquals(List.of(
                new ChunkCoord(0, 0, 0),
                new ChunkCoord(1, 0, 0),
                new ChunkCoord(0, 0, 1),
                new ChunkCoord(0, 1, 0)
        ), coords);
    }
}
