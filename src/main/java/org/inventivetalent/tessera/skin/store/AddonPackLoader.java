package org.inventivetalent.tessera.skin.store;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Discovers add-on {@code .ztsra} packs that a server admin drops into
 * the plugin folder. Packs are read-only — they sit between the writable
 * runtime store and the jar-bundled resource so they shadow bundled
 * blocks but can themselves be overridden by anything the runtime baker
 * produces.
 *
 * <p>Packs are filtered by {@code chunkGridSize}: a pack whose manifest
 * declares a different grid is skipped with a warning. Packs without a
 * readable manifest are skipped too (treated as malformed). Packs that
 * pass the filter are returned sorted by filename so the layering order
 * is deterministic — useful when two packs supply the same block and an
 * admin needs to pick a winner by renaming.
 */
public final class AddonPackLoader {

    private AddonPackLoader() {}

    public record LoadedAddon(Path source, TsraZipStore store) {}

    /**
     * Scan {@code dir} for {@code *.ztsra} files matching {@code expectedGridN}.
     * Returns an empty list if the directory doesn't exist; the caller
     * doesn't need to pre-create it.
     */
    public static List<LoadedAddon> load(Logger logger, Path dir, int expectedGridN) {
        if (!Files.isDirectory(dir)) return List.of();

        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*" + TsraFormat.ZIP_EXTENSION)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p)) paths.add(p);
            }
        } catch (IOException io) {
            logger.log(Level.WARNING, "[heads-addons] failed to list " + dir, io);
            return List.of();
        }
        paths.sort(Comparator.comparing(p -> p.getFileName().toString()));

        List<LoadedAddon> loaded = new ArrayList<>(paths.size());
        for (Path p : paths) {
            TsraZipStore store;
            try {
                store = TsraZipStore.fromFile(logger, p);
            } catch (IOException io) {
                logger.warning("[heads-addons] failed to open " + p.getFileName() + ": " + io.getMessage());
                continue;
            }
            Optional<TsraFormat.Manifest> manifest = store.manifest();
            if (manifest.isEmpty()) {
                logger.warning("[heads-addons] " + p.getFileName() + " has no manifest; skipping");
                store.close();
                continue;
            }
            int gridN = manifest.get().gridN();
            if (gridN != expectedGridN) {
                logger.info("[heads-addons] " + p.getFileName() + " is for gridN=" + gridN
                        + ", current chunkGridSize is " + expectedGridN + "; skipping");
                store.close();
                continue;
            }
            logger.info("[heads-addons] loaded " + p.getFileName()
                    + " (gridN=" + gridN + ", " + store.listBlocks().size() + " blocks)");
            loaded.add(new LoadedAddon(p, store));
        }
        return loaded;
    }
}
