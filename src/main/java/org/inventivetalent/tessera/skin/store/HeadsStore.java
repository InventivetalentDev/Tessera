package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;

import java.io.Closeable;
import java.util.Collection;
import java.util.Optional;

/**
 * Backend storage abstraction for the new {@code .tsra} layout. Reads are
 * required; writes are optional (zip-backed bundled stores return
 * {@link UnsupportedOperationException} from the write methods). The
 * registry layers a writable {@link TsraFolderStore} on top of an optional
 * read-only {@link TsraZipStore} so runtime bakes shadow bundled entries
 * without mutating the jar resource.
 */
public interface HeadsStore extends Closeable {

    Optional<TsraFormat.Manifest> manifest();

    /** Enumerate every block known to this store. Order is unspecified. */
    Collection<BakeKey> listBlocks();

    /**
     * Read one block's chunk-hash + variant index. Returns empty when the
     * key isn't known to this store; callers that span bundled + runtime
     * layers fall through to the next layer in that case.
     */
    Optional<TsraFormat.Block> readBlock(BakeKey key);

    /** Read one skin payload by content hash. */
    Optional<TsraFormat.Skin> readSkin(String hash);

    default boolean isWritable() { return false; }

    /** Write the manifest. Folder-backed stores call this on first use. */
    default void writeManifest(TsraFormat.Manifest manifest) {
        throw new UnsupportedOperationException("read-only store");
    }

    default void writeBlock(TsraFormat.Block block) {
        throw new UnsupportedOperationException("read-only store");
    }

    default void writeSkin(TsraFormat.Skin skin) {
        throw new UnsupportedOperationException("read-only store");
    }

    /** Remove every {@link BakeKey} for {@code block} (every tinted variant included). */
    default void removeBlock(BlockKey block) {
        throw new UnsupportedOperationException("read-only store");
    }

    /** Remove every block. Skin payloads are retained — they're dedup'd by hash and may still be referenced. */
    default void clearBlocks() {
        throw new UnsupportedOperationException("read-only store");
    }

    @Override default void close() {}
}
