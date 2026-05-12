package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.core.BakeKey;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Read-only {@link HeadsStore} backed by a {@code .ztsra} zip — the format
 * Tessera ships its bundled heads in. The zip mirrors the
 * {@link TsraFolderStore} layout entry-for-entry (a {@code manifest.tsra},
 * a {@code blocks/} subdir and a sharded {@code skins/} subdir) so the
 * same {@link TsraFormat} codec round-trips through either backend.
 *
 * <p>Two construction modes:
 * <ul>
 *   <li>{@link #fromFile(Logger, java.nio.file.Path)} — random-access via
 *       {@link ZipFile} when the zip lives on the filesystem. Per-entry
 *       reads stay cheap so {@code readSkin(hash)} only inflates the one
 *       payload the caller asked for.</li>
 *   <li>{@link #fromClasspath(Logger, String)} — eager unpack into an
 *       in-memory map. Required because resources shipped in the plugin jar
 *       are nested zip-in-zip; standard library APIs can't open the inner
 *       zip without writing it out, and the bundled file is small enough
 *       (~2 MB / 1.4 k entries) that holding it in memory is the simpler
 *       trade-off.</li>
 * </ul>
 */
public final class TsraZipStore implements HeadsStore {

    private final Logger logger;
    private final ZipFile zipFile;            // null in classpath mode
    private final java.util.Map<String, byte[]> entries; // null in file mode
    private final TsraFormat.Manifest manifest;

    private TsraZipStore(Logger logger, ZipFile zipFile,
                         java.util.Map<String, byte[]> entries,
                         TsraFormat.Manifest manifest) {
        this.logger = logger;
        this.zipFile = zipFile;
        this.entries = entries;
        this.manifest = manifest;
    }

    public static TsraZipStore fromFile(Logger logger, java.nio.file.Path path) throws IOException {
        ZipFile zf = new ZipFile(path.toFile());
        TsraFormat.Manifest manifest = readManifestFile(logger, zf);
        return new TsraZipStore(logger, zf, null, manifest);
    }

    /**
     * Stream a {@code .ztsra} from a classpath resource into memory and
     * return an open store. Returns {@link Optional#empty()} when the
     * resource doesn't exist (callers fall back to "no bundled file").
     */
    public static Optional<TsraZipStore> fromClasspath(Logger logger, String resource) {
        try (InputStream in = TsraZipStore.class.getResourceAsStream(resource)) {
            if (in == null) return Optional.empty();
            java.util.Map<String, byte[]> map = new java.util.HashMap<>();
            try (ZipInputStream zis = new ZipInputStream(in)) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    map.put(e.getName(), readAll(zis));
                }
            }
            TsraFormat.Manifest manifest = readManifestBytes(logger, map.get(TsraFormat.MANIFEST_NAME));
            return Optional.of(new TsraZipStore(logger, null, map, manifest));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-zip] failed to read classpath " + resource, io);
            return Optional.empty();
        }
    }

    @Override public Optional<TsraFormat.Manifest> manifest() { return Optional.ofNullable(manifest); }

    @Override
    public Collection<BakeKey> listBlocks() {
        List<BakeKey> out = new ArrayList<>();
        forEachEntryName(name -> {
            if (!name.startsWith(TsraFormat.BLOCKS_DIR + "/")) return;
            if (!name.endsWith(TsraFormat.FILE_EXTENSION)) return;
            String filename = name.substring(name.lastIndexOf('/') + 1);
            try {
                out.add(TsraFormat.parseBlockFilename(filename));
            } catch (RuntimeException re) {
                logger.warning("[tsra-zip] skipping unparseable block " + filename);
            }
        });
        return out;
    }

    @Override
    public Optional<TsraFormat.Block> readBlock(BakeKey key) {
        String name = TsraFormat.BLOCKS_DIR + "/" + TsraFormat.blockFilename(key);
        byte[] bytes = readEntry(name);
        if (bytes == null) return Optional.empty();
        try {
            return Optional.of(TsraFormat.readBlock(bytes));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-zip] failed to parse " + name, io);
            return Optional.empty();
        }
    }

    @Override
    public Optional<TsraFormat.Skin> readSkin(String hash) {
        byte[] bytes = readEntry(TsraFormat.skinPath(hash));
        if (bytes == null) return Optional.empty();
        try {
            return Optional.of(TsraFormat.readSkin(hash, bytes));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-zip] failed to parse skin " + hash, io);
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        if (zipFile != null) {
            try { zipFile.close(); } catch (IOException ignored) {}
        }
    }

    private byte[] readEntry(String name) {
        if (entries != null) return entries.get(name);
        ZipEntry e = zipFile.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zipFile.getInputStream(e)) {
            return readAll(in);
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-zip] failed to read " + name, io);
            return null;
        }
    }

    private void forEachEntryName(java.util.function.Consumer<String> consumer) {
        if (entries != null) {
            entries.keySet().forEach(consumer);
            return;
        }
        Enumeration<? extends ZipEntry> en = zipFile.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (!e.isDirectory()) consumer.accept(e.getName());
        }
    }

    private static TsraFormat.Manifest readManifestFile(Logger logger, ZipFile zf) {
        ZipEntry e = zf.getEntry(TsraFormat.MANIFEST_NAME);
        if (e == null) return null;
        try (InputStream in = zf.getInputStream(e)) {
            return TsraFormat.readManifest(readAll(in));
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-zip] bad manifest", io);
            return null;
        }
    }

    private static TsraFormat.Manifest readManifestBytes(Logger logger, byte[] bytes) {
        if (bytes == null) return null;
        try {
            return TsraFormat.readManifest(bytes);
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-zip] bad manifest", io);
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

}
