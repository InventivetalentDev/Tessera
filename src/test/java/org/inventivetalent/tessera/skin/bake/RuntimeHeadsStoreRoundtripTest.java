package org.inventivetalent.tessera.skin.bake;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down that {@link RuntimeHeadsStore} round-trips entries across a
 * simulated server restart. Catches the class of bug where over-eager
 * mismatch checks (gridN, version, …) discard a perfectly valid cache and
 * force every block to re-bake.
 */
class RuntimeHeadsStoreRoundtripTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void roundTripPreservesEntriesAcrossRestart(@TempDir Path tmp) {
        Path cacheFile = tmp.resolve("heads-4.json");

        HeadsRegistry reg1 = HeadsRegistry.empty(LOG, 4, "1.21.4");
        RuntimeHeadsStore store1 = new RuntimeHeadsStore(LOG, cacheFile, reg1.gridN(), reg1.version());
        store1.loadInto(reg1);
        reg1.setPersistence(store1);

        BakeKey untinted = BakeKey.untinted(BlockKey.of("minecraft:obsidian"));
        BakeKey tinted = new BakeKey(BlockKey.of("minecraft:grass_block"), 0xFF7fbf2e);
        reg1.register(untinted, Map.of(
                new ChunkCoord(0, 0, 0), entry("h-obs"),
                new ChunkCoord(1, 0, 0), entry("h-obs2")), Map.of());
        reg1.register(tinted, Map.of(new ChunkCoord(0, 0, 0), entry("h-grass")), Map.of());

        // Simulate restart: brand new registry + store reading the same file.
        HeadsRegistry reg2 = HeadsRegistry.empty(LOG, 4, "1.21.4");
        RuntimeHeadsStore store2 = new RuntimeHeadsStore(LOG, cacheFile, reg2.gridN(), reg2.version());
        store2.loadInto(reg2);

        assertTrue(reg2.has(untinted), "untinted entry should survive restart");
        assertTrue(reg2.has(tinted), "tinted entry should survive restart");
        assertEquals("h-obs", reg2.get(untinted, new ChunkCoord(0, 0, 0)).orElseThrow().skinHash());
        assertEquals("h-grass", reg2.get(tinted, new ChunkCoord(0, 0, 0)).orElseThrow().skinHash());
    }

    @Test
    void cacheLoadsEvenWhenRegistryVersionDiffersFromCacheFile(@TempDir Path tmp) {
        // Regression: the registry's `version` reflects the bundled
        // heads-N.json's bake-time version, not the running MC version. If
        // the bundled file is re-baked at a different `--version`, an
        // existing cache written under the old version must still load —
        // its chunk coordinates and skin texture are still valid for the
        // current grid size.
        Path cacheFile = tmp.resolve("heads-4.json");

        // First boot: registry says "1.21.4", cache written with "1.21.4".
        HeadsRegistry first = HeadsRegistry.empty(LOG, 4, "1.21.4");
        RuntimeHeadsStore store1 = new RuntimeHeadsStore(LOG, cacheFile, 4, "1.21.4");
        store1.loadInto(first);
        first.setPersistence(store1);
        BakeKey key = BakeKey.untinted(BlockKey.of("minecraft:obsidian"));
        first.register(key, Map.of(new ChunkCoord(0, 0, 0), entry("h-obs")), Map.of());

        // Second boot after plugin upgrade: bundled file now declares
        // version "1.21.5", registry.version() = "1.21.5". Cache file still
        // has "1.21.4". The cache must still load.
        HeadsRegistry second = HeadsRegistry.empty(LOG, 4, "1.21.5");
        RuntimeHeadsStore store2 = new RuntimeHeadsStore(LOG, cacheFile, 4, "1.21.5");
        store2.loadInto(second);
        assertTrue(second.has(key),
                "cache should survive a bundled-file version bump; otherwise every block re-bakes");
    }

    private static HeadsRegistry.Entry entry(String hash) {
        return new HeadsRegistry.Entry(hash, "value-" + hash, "sig-" + hash, "uuid-" + hash);
    }
}
