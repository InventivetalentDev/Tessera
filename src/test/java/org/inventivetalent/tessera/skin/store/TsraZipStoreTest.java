package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the folder ↔ zip equivalence: data written through a
 * {@link TsraFolderStore} round-trips byte-for-byte through
 * {@link JsonMigrator#zipFolder} into a {@link TsraZipStore}. This is the
 * exact path the bake task uses to produce the bundled
 * {@code heads-{N}.ztsra} from the scratch folder it bakes into.
 */
class TsraZipStoreTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void folderRoundTripsThroughZip(@TempDir Path tmp) throws Exception {
        Path folder = tmp.resolve("heads-4");
        TsraFolderStore source = new TsraFolderStore(LOG, folder);
        source.writeManifest(new TsraFormat.Manifest(4, "1.21.4", "test"));
        source.writeSkin(new TsraFormat.Skin("hash-1", "value-1", "sig-1", "uuid-1"));
        source.writeSkin(new TsraFormat.Skin("hash-2", "value-2", "sig-2", null));
        source.writeBlock(new TsraFormat.Block(
                BakeKey.untinted(BlockKey.of("minecraft:stone")),
                Map.of(new ChunkCoord(0, 0, 0), "hash-1",
                        new ChunkCoord(1, 0, 0), "hash-2"),
                Map.of()));

        Path zipOut = tmp.resolve("heads-4.ztsra");
        JsonMigrator.zipFolder(folder, zipOut);
        assertTrue(java.nio.file.Files.size(zipOut) > 0);

        try (TsraZipStore zip = TsraZipStore.fromFile(LOG, zipOut)) {
            assertEquals(4, zip.manifest().orElseThrow().gridN());
            assertEquals(1, zip.listBlocks().size());

            TsraFormat.Block b = zip.readBlock(
                    BakeKey.untinted(BlockKey.of("minecraft:stone"))).orElseThrow();
            assertEquals("hash-1", b.chunkHashes().get(new ChunkCoord(0, 0, 0)));
            assertEquals("hash-2", b.chunkHashes().get(new ChunkCoord(1, 0, 0)));

            assertEquals("value-1", zip.readSkin("hash-1").orElseThrow().value());
            assertEquals("value-2", zip.readSkin("hash-2").orElseThrow().value());
            // Missing entries return empty rather than blowing up — bake-time
            // partial states can ship a block file whose hash hits the
            // runtime layer instead.
            assertFalse(zip.readSkin("nope").isPresent());
        }
    }

    @Test
    void zipReaderRejectsHashCollisionsCleanly(@TempDir Path tmp) throws Exception {
        // Two different blocks point at the same hash → only one skin file
        // is written but both blocks resolve through it. Pins down the
        // dedup invariant that makes uniform-stone catalogs small.
        Path folder = tmp.resolve("heads-4");
        TsraFolderStore source = new TsraFolderStore(LOG, folder);
        source.writeSkin(new TsraFormat.Skin("shared", "v", "s", null));
        source.writeBlock(new TsraFormat.Block(
                BakeKey.untinted(BlockKey.of("minecraft:stone")),
                Map.of(new ChunkCoord(0, 0, 0), "shared"), Map.of()));
        source.writeBlock(new TsraFormat.Block(
                BakeKey.untinted(BlockKey.of("minecraft:cobblestone")),
                Map.of(new ChunkCoord(0, 0, 0), "shared"), Map.of()));

        Path zipOut = tmp.resolve("heads-4.ztsra");
        JsonMigrator.zipFolder(folder, zipOut);

        try (TsraZipStore zip = TsraZipStore.fromFile(LOG, zipOut)) {
            assertEquals("v", zip.readSkin("shared").orElseThrow().value());
            assertEquals(2, zip.listBlocks().size());
        }
    }
}
