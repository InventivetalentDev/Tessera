package org.inventivetalent.tessera.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VariantKeyTest {

    @Test
    void exactMatchReturnsFullKey() {
        Set<String> candidates = Set.of("axis=x", "axis=y", "axis=z");
        assertEquals("axis=y", VariantKey.pickMatching("axis=y", candidates));
    }

    @Test
    void emptyCandidatesReturnEmpty() {
        assertEquals("", VariantKey.pickMatching("axis=y", Set.of()));
    }

    @Test
    void dropsNuisancePropertiesToFindMatch() {
        // Stairs blockstate variants only branch on facing+half+shape — so a
        // BlockData string that includes waterlogged should still match after
        // dropping it.
        Set<String> candidates = Set.of(
                "facing=north,half=bottom,shape=straight",
                "facing=east,half=bottom,shape=straight");
        String full = "facing=north,half=bottom,shape=straight,waterlogged=false";
        assertEquals("facing=north,half=bottom,shape=straight",
                VariantKey.pickMatching(full, candidates));
    }

    @Test
    void dropsLaterPropertiesWhenNoNuisanceMatch() {
        // If only `axis` matters but the full key has both axis and an unknown
        // extra property, drop later props until match found.
        Set<String> candidates = Set.of("axis=y");
        assertEquals("axis=y",
                VariantKey.pickMatching("axis=y,zzzz=foo", candidates));
    }

    @Test
    void noMatchReturnsEmpty() {
        Set<String> candidates = Set.of("axis=x", "axis=y");
        assertEquals("", VariantKey.pickMatching("facing=north", candidates));
    }

    @Test
    void nullFullKeyReturnsEmpty() {
        assertEquals("", VariantKey.pickMatching(null, Set.of("axis=y")));
    }
}
