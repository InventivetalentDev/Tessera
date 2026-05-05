package org.inventivetalent.tessera.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockKeyTest {

    @Test
    void of_addsMinecraftNamespaceWhenMissing() {
        BlockKey key = BlockKey.of("stone");
        assertEquals("minecraft", key.namespace());
        assertEquals("stone", key.path());
        assertEquals("minecraft:stone", key.asString());
    }

    @Test
    void of_splitsExplicitNamespace() {
        BlockKey key = BlockKey.of("tessera:custom_block");
        assertEquals("tessera", key.namespace());
        assertEquals("custom_block", key.path());
    }

    @Test
    void of_lowercasesInput() {
        BlockKey key = BlockKey.of("Minecraft:Oak_Log");
        assertEquals("minecraft:oak_log", key.asString());
    }

    @Test
    void toStringMatchesAsString() {
        BlockKey key = BlockKey.of("minecraft:stone");
        assertEquals(key.asString(), key.toString());
    }

    @Test
    void rejectsEmptyComponents() {
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("", "stone"));
        assertThrows(IllegalArgumentException.class, () -> new BlockKey("minecraft", ""));
    }
}
