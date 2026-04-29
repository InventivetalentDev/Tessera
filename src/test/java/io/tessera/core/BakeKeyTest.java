package io.tessera.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BakeKeyTest {

    @Test
    void untintedFactoryProducesZeroTint() {
        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:stone"));
        assertEquals(0, key.tintArgb());
        assertFalse(key.isTinted());
    }

    @Test
    void nonZeroTintIsTinted() {
        BakeKey key = new BakeKey(BlockKey.of("minecraft:oak_leaves"), 0xFF71A74D);
        assertTrue(key.isTinted());
    }

    @Test
    void differentTintsAreDistinctKeys() {
        BlockKey block = BlockKey.of("minecraft:oak_leaves");
        BakeKey plains = new BakeKey(block, 0xFF71A74D);
        BakeKey forest = new BakeKey(block, 0xFF59AE30);
        assertNotEquals(plains, forest);
        assertNotEquals(plains.hashCode(), forest.hashCode());
    }

    @Test
    void sameBlockAndTintAreEqual() {
        BlockKey block = BlockKey.of("minecraft:grass_block");
        assertEquals(new BakeKey(block, 0xFF5FAE3C), new BakeKey(block, 0xFF5FAE3C));
    }

    @Test
    void toStringOmitsTintWhenUntinted() {
        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:stone"));
        assertEquals("minecraft:stone", key.toString());
    }

    @Test
    void toStringIncludesHexTintWhenTinted() {
        BakeKey key = new BakeKey(BlockKey.of("minecraft:oak_leaves"), 0xFF71A74D);
        assertEquals("minecraft:oak_leaves#71a74d", key.toString());
    }

    @Test
    void rejectsNullBlock() {
        assertThrows(NullPointerException.class, () -> new BakeKey(null, 0));
    }
}
