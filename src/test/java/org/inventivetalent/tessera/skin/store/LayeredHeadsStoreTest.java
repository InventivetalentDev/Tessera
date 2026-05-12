package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layered semantics: writable beats every read-only layer, each
 * read-only layer beats the ones declared after it, and {@code listBlocks}
 * is the union across the stack. This is what lets a server admin ship a
 * small bundled set of blocks with the jar and add more via dropped-in
 * addon packs without losing either.
 */
class LayeredHeadsStoreTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void readsFallThroughInPriorityOrder(@TempDir Path tmp) {
        TsraFolderStore writable = new TsraFolderStore(LOG, tmp.resolve("writable"));
        TsraFolderStore addon = new TsraFolderStore(LOG, tmp.resolve("addon"));
        TsraFolderStore bundled = new TsraFolderStore(LOG, tmp.resolve("bundled"));

        // Each layer owns a distinct block plus one shared block they
        // disagree on, so we can pin which copy wins.
        BakeKey stone = BakeKey.untinted(BlockKey.of("minecraft:stone"));
        BakeKey diorite = BakeKey.untinted(BlockKey.of("minecraft:diorite"));
        BakeKey granite = BakeKey.untinted(BlockKey.of("minecraft:granite"));
        BakeKey shared = BakeKey.untinted(BlockKey.of("minecraft:cobblestone"));

        writable.writeSkin(new TsraFormat.Skin("h-w", "writable", "s", null));
        writable.writeBlock(new TsraFormat.Block(stone,
                Map.of(new ChunkCoord(0, 0, 0), "h-w"), Map.of()));
        writable.writeSkin(new TsraFormat.Skin("h-shared-w", "writable-shared", "s", null));
        writable.writeBlock(new TsraFormat.Block(shared,
                Map.of(new ChunkCoord(0, 0, 0), "h-shared-w"), Map.of()));

        addon.writeSkin(new TsraFormat.Skin("h-a", "addon", "s", null));
        addon.writeBlock(new TsraFormat.Block(diorite,
                Map.of(new ChunkCoord(0, 0, 0), "h-a"), Map.of()));
        addon.writeSkin(new TsraFormat.Skin("h-shared-a", "addon-shared", "s", null));
        addon.writeBlock(new TsraFormat.Block(shared,
                Map.of(new ChunkCoord(0, 0, 0), "h-shared-a"), Map.of()));

        bundled.writeSkin(new TsraFormat.Skin("h-b", "bundled", "s", null));
        bundled.writeBlock(new TsraFormat.Block(granite,
                Map.of(new ChunkCoord(0, 0, 0), "h-b"), Map.of()));
        bundled.writeSkin(new TsraFormat.Skin("h-shared-b", "bundled-shared", "s", null));
        bundled.writeBlock(new TsraFormat.Block(shared,
                Map.of(new ChunkCoord(0, 0, 0), "h-shared-b"), Map.of()));

        LayeredHeadsStore stack = new LayeredHeadsStore(writable, List.of(addon, bundled));

        // Each layer's exclusive block is visible.
        assertTrue(stack.readBlock(stone).isPresent());
        assertTrue(stack.readBlock(diorite).isPresent());
        assertTrue(stack.readBlock(granite).isPresent());
        assertEquals(4, stack.listBlocks().size(),
                "listBlocks must union across every layer — addon packs are additive");

        // Shared block: writable wins.
        assertEquals("h-shared-w", stack.readBlock(shared).orElseThrow()
                .chunkHashes().get(new ChunkCoord(0, 0, 0)));
    }

    @Test
    void writesOnlyTouchWritableLayer(@TempDir Path tmp) {
        TsraFolderStore writable = new TsraFolderStore(LOG, tmp.resolve("writable"));
        TsraFolderStore addon = new TsraFolderStore(LOG, tmp.resolve("addon"));
        LayeredHeadsStore stack = new LayeredHeadsStore(writable, List.of(addon));

        stack.writeSkin(new TsraFormat.Skin("h", "v", "s", null));
        stack.writeBlock(new TsraFormat.Block(
                BakeKey.untinted(BlockKey.of("minecraft:stone")),
                Map.of(new ChunkCoord(0, 0, 0), "h"), Map.of()));

        assertTrue(writable.readSkin("h").isPresent());
        assertFalse(addon.readSkin("h").isPresent(),
                "Addon layer must not be mutated by stack writes");
    }

    @Test
    void clearBlocksOnlyClearsWritable(@TempDir Path tmp) {
        TsraFolderStore writable = new TsraFolderStore(LOG, tmp.resolve("writable"));
        TsraFolderStore addon = new TsraFolderStore(LOG, tmp.resolve("addon"));

        BakeKey addonBlock = BakeKey.untinted(BlockKey.of("minecraft:granite"));
        addon.writeSkin(new TsraFormat.Skin("h-a", "v", "s", null));
        addon.writeBlock(new TsraFormat.Block(addonBlock,
                Map.of(new ChunkCoord(0, 0, 0), "h-a"), Map.of()));

        BakeKey writableBlock = BakeKey.untinted(BlockKey.of("minecraft:stone"));
        writable.writeSkin(new TsraFormat.Skin("h-w", "v", "s", null));
        writable.writeBlock(new TsraFormat.Block(writableBlock,
                Map.of(new ChunkCoord(0, 0, 0), "h-w"), Map.of()));

        LayeredHeadsStore stack = new LayeredHeadsStore(writable, List.of(addon));
        stack.clearBlocks();

        // Addon block survives; writable block is gone.
        assertTrue(stack.readBlock(addonBlock).isPresent(),
                "clearBlocks() must not mutate read-only layers");
        assertFalse(stack.readBlock(writableBlock).isPresent());
    }

    @Test
    void emptyReadOnlyListIsValid(@TempDir Path tmp) {
        TsraFolderStore writable = new TsraFolderStore(LOG, tmp.resolve("writable"));
        LayeredHeadsStore stack = new LayeredHeadsStore(writable, List.of());
        assertEquals(0, stack.listBlocks().size());
    }
}
