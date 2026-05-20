package org.inventivetalent.tessera.skin;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.skin.store.TsraFolderStore;
import org.inventivetalent.tessera.skin.store.TsraFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the registry + folder-store stack. Covers the two
 * properties the registry's lazy-loading layout has to guarantee:
 * <ol>
 *   <li>Skin payloads load from disk on demand without a separate boot-time
 *       pre-pass — {@code populateIndex} only walks block files.</li>
 *   <li>{@code register} writes through to the store so a fresh registry
 *       built over the same folder sees the previously-registered entries
 *       (the restart-survival property of the retired
 *       {@code RuntimeHeadsStoreRoundtripTest}).</li>
 * </ol>
 */
class HeadsRegistryStoreTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void lazyLoadsPayloadsFromStoreOnDemand(@TempDir Path tmp) {
        // Seed the store directly, then construct a registry over it. The
        // registry's index pass shouldn't pull skin payloads into memory —
        // those should only show up when a caller actually asks for them.
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        store.writeManifest(new TsraFormat.Manifest(4, "1.21.4", "test"));
        store.writeSkin(new TsraFormat.Skin("h", "value", "sig", "uuid"));
        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:stone"));
        store.writeBlock(TsraFormat.Block.singleShape(key,
                Map.of(new ChunkCoord(0, 0, 0), "h",
                        new ChunkCoord(1, 0, 0), "h"),
                Map.of()));

        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.4", 64);
        assertTrue(reg.has(key));

        HeadsRegistry.Entry resolved = reg.get(key, new ChunkCoord(0, 0, 0)).orElseThrow();
        assertEquals("value", resolved.textureValue());
        assertEquals("uuid", resolved.mineskinUuid());
    }

    @Test
    void registerWritesThroughAcrossRestart(@TempDir Path tmp) {
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        HeadsRegistry first = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.4", 64);

        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:obsidian"));
        first.register(key, Map.of(new ChunkCoord(0, 0, 0), entry("h-obs")), Map.of());

        // "Restart" — fresh store + registry over the same folder.
        TsraFolderStore reopened = new TsraFolderStore(LOG, tmp);
        HeadsRegistry second = HeadsRegistry.loadFrom(LOG, reopened, 4, "1.21.4", 64);
        assertTrue(second.has(key));
        assertEquals("value-h-obs",
                second.get(key, new ChunkCoord(0, 0, 0)).orElseThrow().textureValue());
    }

    @Test
    void warmPopulatesCacheWithoutResolvingChunks(@TempDir Path tmp) {
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        store.writeSkin(new TsraFormat.Skin("h1", "v1", "s1", null));
        store.writeSkin(new TsraFormat.Skin("h2", "v2", "s2", null));
        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:diorite"));
        store.writeBlock(TsraFormat.Block.singleShape(key,
                Map.of(new ChunkCoord(0, 0, 0), "h1",
                        new ChunkCoord(1, 0, 0), "h1",   // dupe — warm dedupes
                        new ChunkCoord(2, 0, 0), "h2"),
                Map.of()));

        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.4", 64);
        int warmed = reg.warm(key);
        assertEquals(2, warmed,
                "warm() should load each unique hash once — dupe chunks must not double-count");

        // Subsequent warm is a no-op since the cache is already hot.
        assertEquals(0, reg.warm(key));
    }

    @Test
    void invalidateRemovesFromStore(@TempDir Path tmp) {
        TsraFolderStore store = new TsraFolderStore(LOG, tmp);
        HeadsRegistry reg = HeadsRegistry.loadFrom(LOG, store, 4, "1.21.4", 64);
        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:stone"));
        reg.register(key, Map.of(new ChunkCoord(0, 0, 0), entry("h")), Map.of());

        reg.invalidate(key.block());

        // The store layer should have lost the block file too — a fresh
        // registry shouldn't see it.
        TsraFolderStore reopened = new TsraFolderStore(LOG, tmp);
        HeadsRegistry second = HeadsRegistry.loadFrom(LOG, reopened, 4, "1.21.4", 64);
        assertFalse(second.has(key));
    }

    private static HeadsRegistry.Entry entry(String hash) {
        return new HeadsRegistry.Entry(hash, "value-" + hash, "sig-" + hash, "uuid-" + hash);
    }
}
