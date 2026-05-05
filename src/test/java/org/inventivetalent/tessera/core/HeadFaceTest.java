package org.inventivetalent.tessera.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadFaceTest {

    @Test
    void everyFaceIsEightByEight() {
        for (HeadFace hf : HeadFace.values()) {
            assertEquals(8, hf.width(), hf + " width");
            assertEquals(8, hf.height(), hf + " height");
        }
    }

    @Test
    void uvRectsStayInside64x64Canvas() {
        for (HeadFace hf : HeadFace.values()) {
            assertTrue(hf.u0 >= 0 && hf.u1 <= 64, hf + " U out of bounds");
            assertTrue(hf.v0 >= 0 && hf.v1 <= 64, hf + " V out of bounds");
        }
    }

    @Test
    void packOrderCoversEveryFaceExactlyOnce() {
        assertEquals(HeadFace.values().length, HeadFace.PACK_ORDER.length);
        Set<HeadFace> seen = new HashSet<>(Arrays.asList(HeadFace.PACK_ORDER));
        assertEquals(HeadFace.values().length, seen.size());
    }
}
