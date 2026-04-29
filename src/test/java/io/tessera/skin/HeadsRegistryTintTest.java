package io.tessera.skin;

import io.tessera.core.BakeKey;
import io.tessera.core.BlockKey;
import io.tessera.core.ChunkCoord;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down that tinted runtime variants are stored as separate registry
 * entries from the canonical untinted entry, and that the BlockKey
 * convenience overloads always resolve to the untinted slot.
 */
class HeadsRegistryTintTest {

    private static final BlockKey LEAVES = BlockKey.of("minecraft:oak_leaves");
    private static final ChunkCoord COORD = new ChunkCoord(0, 0, 0);

    @Test
    void registerSeparatesTintedFromUntinted() {
        HeadsRegistry reg = HeadsRegistry.empty(Logger.getAnonymousLogger(), 4, "1.21.4");
        HeadsRegistry.Entry untinted = entry("hash-untinted");
        HeadsRegistry.Entry plains = entry("hash-plains");

        reg.register(BakeKey.untinted(LEAVES), Map.of(COORD, untinted));
        reg.register(new BakeKey(LEAVES, 0xFF71A74D), Map.of(COORD, plains));

        assertEquals(untinted, reg.chunksFor(BakeKey.untinted(LEAVES)).get(COORD));
        assertEquals(plains, reg.chunksFor(new BakeKey(LEAVES, 0xFF71A74D)).get(COORD));
    }

    @Test
    void blockKeyOverloadResolvesToUntintedEntry() {
        HeadsRegistry reg = HeadsRegistry.empty(Logger.getAnonymousLogger(), 4, "1.21.4");
        reg.register(BakeKey.untinted(LEAVES), Map.of(COORD, entry("untinted")));
        reg.register(new BakeKey(LEAVES, 0xFF71A74D), Map.of(COORD, entry("tinted")));

        // has(BlockKey) and chunksFor(BlockKey) are the legacy convenience
        // overloads — they must always hit the untinted slot, never a
        // runtime tinted variant, otherwise debug commands like
        // /tessera test would surface a biome-specific bake.
        assertTrue(reg.has(LEAVES));
        assertEquals("untinted", reg.chunksFor(LEAVES).get(COORD).skinHash());
    }

    @Test
    void invalidateBlockKeyClearsAllTintedVariants() {
        HeadsRegistry reg = HeadsRegistry.empty(Logger.getAnonymousLogger(), 4, "1.21.4");
        reg.register(BakeKey.untinted(LEAVES), Map.of(COORD, entry("u")));
        reg.register(new BakeKey(LEAVES, 0xFF71A74D), Map.of(COORD, entry("plains")));
        reg.register(new BakeKey(LEAVES, 0xFF59AE30), Map.of(COORD, entry("forest")));

        assertTrue(reg.invalidate(LEAVES));

        assertFalse(reg.has(BakeKey.untinted(LEAVES)));
        assertFalse(reg.has(new BakeKey(LEAVES, 0xFF71A74D)));
        assertFalse(reg.has(new BakeKey(LEAVES, 0xFF59AE30)));
    }

    @Test
    void hasBakeKeyDistinguishesTintedFromUntinted() {
        HeadsRegistry reg = HeadsRegistry.empty(Logger.getAnonymousLogger(), 4, "1.21.4");
        reg.register(new BakeKey(LEAVES, 0xFF71A74D), Map.of(COORD, entry("plains")));

        assertFalse(reg.has(BakeKey.untinted(LEAVES)));
        assertTrue(reg.has(new BakeKey(LEAVES, 0xFF71A74D)));
        assertFalse(reg.has(new BakeKey(LEAVES, 0xFF59AE30)));
    }

    private static HeadsRegistry.Entry entry(String hash) {
        return new HeadsRegistry.Entry(hash, "value-" + hash, "sig-" + hash, null);
    }
}
