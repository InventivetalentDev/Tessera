package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the addon discovery rules: scan the directory, accept matching
 * gridN, reject mismatched gridN, sort by filename, and skip unreadable
 * files cleanly.
 */
class AddonPackLoaderTest {

    private static final Logger LOG = Logger.getAnonymousLogger();

    @Test
    void loadsMatchingPacksAndSortsByFilename(@TempDir Path tmp) throws IOException {
        Path packsDir = tmp.resolve("heads");
        Files.createDirectories(packsDir);

        // Two valid packs at gridN=4. Use names that sort distinctly so we
        // can verify the loader's ordering.
        writePack(tmp, packsDir.resolve("alpha.ztsra"), 4, "minecraft:stone", "h-a");
        writePack(tmp, packsDir.resolve("beta.ztsra"), 4, "minecraft:granite", "h-b");

        List<AddonPackLoader.LoadedAddon> loaded = AddonPackLoader.load(LOG, packsDir, 4);

        assertEquals(2, loaded.size());
        assertEquals("alpha.ztsra", loaded.get(0).source().getFileName().toString());
        assertEquals("beta.ztsra", loaded.get(1).source().getFileName().toString());

        assertTrue(loaded.get(0).store().readBlock(
                BakeKey.untinted(BlockKey.of("minecraft:stone"))).isPresent());
        assertTrue(loaded.get(1).store().readBlock(
                BakeKey.untinted(BlockKey.of("minecraft:granite"))).isPresent());

        for (AddonPackLoader.LoadedAddon a : loaded) a.store().close();
    }

    @Test
    void skipsPacksWithMismatchedGridN(@TempDir Path tmp) throws IOException {
        Path packsDir = tmp.resolve("heads");
        Files.createDirectories(packsDir);
        writePack(tmp, packsDir.resolve("ok.ztsra"), 4, "minecraft:stone", "h");
        writePack(tmp, packsDir.resolve("wrong-grid.ztsra"), 8, "minecraft:granite", "h2");

        List<AddonPackLoader.LoadedAddon> loaded = AddonPackLoader.load(LOG, packsDir, 4);
        assertEquals(1, loaded.size());
        assertEquals("ok.ztsra", loaded.get(0).source().getFileName().toString());
        loaded.get(0).store().close();
    }

    @Test
    void emptyDirectoryYieldsEmptyList(@TempDir Path tmp) throws IOException {
        Path packsDir = tmp.resolve("heads");
        Files.createDirectories(packsDir);
        assertEquals(List.of(), AddonPackLoader.load(LOG, packsDir, 4));
    }

    @Test
    void missingDirectoryYieldsEmptyList(@TempDir Path tmp) {
        assertEquals(List.of(), AddonPackLoader.load(LOG, tmp.resolve("does-not-exist"), 4));
    }

    @Test
    void skipsNonZtsraFiles(@TempDir Path tmp) throws IOException {
        Path packsDir = tmp.resolve("heads");
        Files.createDirectories(packsDir);
        writePack(tmp, packsDir.resolve("real.ztsra"), 4, "minecraft:stone", "h");
        Files.writeString(packsDir.resolve("README.md"), "addon docs go here");
        Files.writeString(packsDir.resolve("notes.txt"), "ignore me");

        List<AddonPackLoader.LoadedAddon> loaded = AddonPackLoader.load(LOG, packsDir, 4);
        assertEquals(1, loaded.size());
        loaded.get(0).store().close();
    }

    private static void writePack(Path tmp, Path zipPath, int gridN, String blockId, String hash) throws IOException {
        Path scratch = Files.createTempDirectory(tmp, "pack-");
        TsraFolderStore folder = new TsraFolderStore(LOG, scratch);
        folder.writeManifest(new TsraFormat.Manifest(gridN, "1.21.4", "test"));
        folder.writeSkin(new TsraFormat.Skin(hash, "value", "sig", null));
        folder.writeBlock(TsraFormat.Block.singleShape(
                BakeKey.untinted(BlockKey.of(blockId)),
                Map.of(new ChunkCoord(0, 0, 0), hash),
                Map.of()));
        JsonMigrator.zipFolder(scratch, zipPath);
    }
}
