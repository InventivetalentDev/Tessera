package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writable, on-disk {@link HeadsStore} backed by a directory laid out as:
 * <pre>
 *   {root}/
 *     manifest.tsra
 *     blocks/&lt;encoded-bake-key&gt;.tsra
 *     skins/&lt;hash[0..2]&gt;/&lt;hash&gt;.tsra
 * </pre>
 * One file per block plus one per unique skin. Replaces the legacy
 * monolithic {@code heads-{N}.json} so runtime bakes only rewrite the
 * specific files they touch rather than serializing the whole catalog on
 * every register / invalidate.
 *
 * <p>Writes are atomic (write-to-temp + rename) so a kill mid-bake leaves
 * the catalog consistent: either the new file is fully visible or the old
 * one is untouched. Deletes are best-effort — a stale block file with no
 * matching in-memory entry is treated as orphaned on next startup and
 * reloaded; a stale skin file is simply dead weight until a future
 * {@code /tessera debug rebake} touches the same hash.
 */
public final class TsraFolderStore implements HeadsStore {

    private final Logger logger;
    private final Path root;
    private final Path blocksDir;
    private final Path skinsDir;

    public TsraFolderStore(Logger logger, Path root) {
        this.logger = logger;
        this.root = root;
        this.blocksDir = root.resolve(TsraFormat.BLOCKS_DIR);
        this.skinsDir = root.resolve(TsraFormat.SKINS_DIR);
    }

    public Path root() { return root; }

    @Override public boolean isWritable() { return true; }

    @Override
    public Optional<TsraFormat.Manifest> manifest() {
        Path p = root.resolve(TsraFormat.MANIFEST_NAME);
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            return Optional.of(TsraFormat.readManifest(Files.readAllBytes(p)));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-folder] bad manifest " + p, io);
            return Optional.empty();
        }
    }

    @Override
    public Collection<BakeKey> listBlocks() {
        if (!Files.isDirectory(blocksDir)) return Collections.emptyList();
        List<BakeKey> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(blocksDir, "*" + TsraFormat.FOLDER_EXTENSION)) {
            for (Path p : stream) {
                try {
                    out.add(TsraFormat.parseBlockFilename(p.getFileName().toString()));
                } catch (RuntimeException re) {
                    logger.warning("[tsra-folder] skipping unparseable block filename " + p.getFileName());
                }
            }
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-folder] failed to list " + blocksDir, io);
        }
        return out;
    }

    @Override
    public Optional<TsraFormat.Block> readBlock(BakeKey key) {
        Path p = blocksDir.resolve(TsraFormat.blockFilename(key));
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            return Optional.of(TsraFormat.readBlock(Files.readAllBytes(p)));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-folder] failed to read " + p, io);
            return Optional.empty();
        }
    }

    @Override
    public Optional<TsraFormat.Skin> readSkin(String hash) {
        Path p = root.resolve(TsraFormat.skinPath(hash));
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            return Optional.of(TsraFormat.readSkin(hash, Files.readAllBytes(p)));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-folder] failed to read " + p, io);
            return Optional.empty();
        }
    }

    @Override
    public void writeManifest(TsraFormat.Manifest manifest) {
        try {
            Files.createDirectories(root);
            atomicWrite(root.resolve(TsraFormat.MANIFEST_NAME), TsraFormat.writeManifest(manifest));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-folder] failed to write manifest", io);
        }
    }

    @Override
    public void writeBlock(TsraFormat.Block block) {
        try {
            Files.createDirectories(blocksDir);
            atomicWrite(blocksDir.resolve(TsraFormat.blockFilename(block.key())),
                    TsraFormat.writeBlock(block));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-folder] failed to write block " + block.key(), io);
        }
    }

    @Override
    public void writeSkin(TsraFormat.Skin skin) {
        Path p = root.resolve(TsraFormat.skinPath(skin.hash()));
        try {
            // Skip the write when an identical-content file already exists.
            // Skin hashes are content-addressed so a hash match means the
            // bytes match; this avoids redundant disk churn when a uniform
            // block re-registers against the same shared skin.
            if (Files.isRegularFile(p)) return;
            Files.createDirectories(p.getParent());
            atomicWrite(p, TsraFormat.writeSkin(skin));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-folder] failed to write skin " + skin.hash(), io);
        }
    }

    @Override
    public void removeBlock(BlockKey block) {
        if (!Files.isDirectory(blocksDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(blocksDir, "*" + TsraFormat.FOLDER_EXTENSION)) {
            for (Path p : stream) {
                try {
                    BakeKey bk = TsraFormat.parseBlockFilename(p.getFileName().toString());
                    if (bk.block().equals(block)) Files.deleteIfExists(p);
                } catch (RuntimeException ignored) {
                    // Skip unparseable filenames - they'll be cleaned up by clearBlocks() if needed.
                }
            }
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-folder] failed to remove " + block, io);
        }
    }

    @Override
    public void clearBlocks() {
        if (!Files.isDirectory(blocksDir)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(blocksDir, "*" + TsraFormat.FOLDER_EXTENSION)) {
            for (Path p : stream) {
                try { Files.deleteIfExists(p); } catch (IOException io) {
                    logger.warning("[tsra-folder] failed to delete " + p + ": " + io.getMessage());
                }
            }
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-folder] failed to clear " + blocksDir, io);
        }
    }

    private static void atomicWrite(Path dst, byte[] bytes) throws IOException {
        Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
