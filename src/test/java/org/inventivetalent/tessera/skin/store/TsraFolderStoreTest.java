package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.assets.model.ModelResolver.VariantRotation;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stress the folder-backed store across read/write/list/remove paths,
 * including the round-trip the runtime relies on for restart-survival.
 * Mirrors the catch-cases from the retired RuntimeHeadsStoreRoundtripTest
 * so the same regressions are still pinned down.
 */
class TsraFolderStoreTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void writeReadListRoundtripsAcrossRestart(@TempDir Path tmp) {
        TsraFolderStore writer = new TsraFolderStore(LOG, tmp);
        writer.writeManifest(new TsraFormat.Manifest(4, "1.21.4", "test"));
        writer.writeSkin(new TsraFormat.Skin("h-obs", "v1", "s1", "u1"));
        writer.writeBlock(new TsraFormat.Block(
                BakeKey.untinted(BlockKey.of("minecraft:obsidian")),
                Map.of(new ChunkCoord(0, 0, 0), "h-obs"),
                Map.of()));

        // "Restart": instantiate a fresh store at the same path.
        TsraFolderStore reader = new TsraFolderStore(LOG, tmp);

        Optional<TsraFormat.Manifest> m = reader.manifest();
        assertTrue(m.isPresent());
        assertEquals(4, m.get().gridN());
        assertEquals("1.21.4", m.get().mcVersion());

        Collection<BakeKey> blocks = reader.listBlocks();
        assertEquals(1, blocks.size());
        BakeKey key = blocks.iterator().next();
        assertEquals("minecraft:obsidian", key.toString());

        TsraFormat.Block b = reader.readBlock(key).orElseThrow();
        assertEquals(1, b.chunkHashes().size());
        assertEquals("h-obs", b.chunkHashes().get(new ChunkCoord(0, 0, 0)));

        TsraFormat.Skin s = reader.readSkin("h-obs").orElseThrow();
        assertEquals("v1", s.value());
        assertEquals("u1", s.mineskinUuid());
    }

    @Test
    void tintedAndUntintedBlocksCoexist(@TempDir Path tmp) {
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        BakeKey untinted = BakeKey.untinted(BlockKey.of("minecraft:grass_block"));
        BakeKey tinted = new BakeKey(BlockKey.of("minecraft:grass_block"), 0xFF7fbf2e);
        store.writeBlock(new TsraFormat.Block(untinted,
                Map.of(new ChunkCoord(0, 0, 0), "h-untinted"), Map.of()));
        store.writeBlock(new TsraFormat.Block(tinted,
                Map.of(new ChunkCoord(0, 0, 0), "h-tinted"), Map.of()));

        // Both files are addressable by their distinct filenames.
        assertEquals("h-untinted", store.readBlock(untinted).orElseThrow().chunkHashes()
                .get(new ChunkCoord(0, 0, 0)));
        assertEquals("h-tinted", store.readBlock(tinted).orElseThrow().chunkHashes()
                .get(new ChunkCoord(0, 0, 0)));
    }

    @Test
    void removeBlockClearsEveryTintedVariant(@TempDir Path tmp) {
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        BlockKey block = BlockKey.of("minecraft:grass_block");
        store.writeBlock(new TsraFormat.Block(BakeKey.untinted(block),
                Map.of(new ChunkCoord(0, 0, 0), "h1"), Map.of()));
        store.writeBlock(new TsraFormat.Block(new BakeKey(block, 0xFF7fbf2e),
                Map.of(new ChunkCoord(0, 0, 0), "h2"), Map.of()));
        store.writeBlock(new TsraFormat.Block(new BakeKey(block, 0xFFff0000),
                Map.of(new ChunkCoord(0, 0, 0), "h3"), Map.of()));

        store.removeBlock(block);

        assertEquals(0, store.listBlocks().size(),
                "removeBlock(block) must clear every tint of the same block");
    }

    @Test
    void writeSkinIsIdempotent(@TempDir Path tmp) {
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        TsraFormat.Skin skin = new TsraFormat.Skin("hash", "value", "sig", "uuid");
        store.writeSkin(skin);
        store.writeSkin(skin);
        store.writeSkin(skin);
        assertEquals(skin, store.readSkin("hash").orElseThrow());
    }

    @Test
    void clearBlocksLeavesSkinsIntact(@TempDir Path tmp) {
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        store.writeSkin(new TsraFormat.Skin("h", "v", "s", null));
        store.writeBlock(new TsraFormat.Block(BakeKey.untinted(BlockKey.of("minecraft:stone")),
                Map.of(new ChunkCoord(0, 0, 0), "h"), Map.of()));
        store.clearBlocks();
        assertEquals(0, store.listBlocks().size());
        assertTrue(store.readSkin("h").isPresent(),
                "clearBlocks() should only drop block files — skins may still be referenced");
    }

    @Test
    void variantRotationsRoundTrip(@TempDir Path tmp) {
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:oak_log"));
        store.writeBlock(new TsraFormat.Block(key,
                Map.of(new ChunkCoord(0, 0, 0), "h"),
                Map.of("axis=x", new VariantRotation(90, 90),
                        "axis=z", new VariantRotation(90, 0))));
        TsraFormat.Block b = store.readBlock(key).orElseThrow();
        assertEquals(new VariantRotation(90, 90), b.variants().get("axis=x"));
        assertEquals(new VariantRotation(90, 0), b.variants().get("axis=z"));
    }

    @Test
    void missingEntriesReturnEmpty(@TempDir Path tmp) {
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        assertFalse(store.readSkin("nope").isPresent());
        assertFalse(store.readBlock(BakeKey.untinted(BlockKey.of("minecraft:nope"))).isPresent());
        assertFalse(store.manifest().isPresent());
    }
}
