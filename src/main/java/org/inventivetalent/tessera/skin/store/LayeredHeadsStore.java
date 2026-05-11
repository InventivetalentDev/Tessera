package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Layers a writable runtime store on top of an optional read-only bundled
 * store. Reads consult the runtime layer first so a re-baked block can
 * shadow the bundled version. Writes always go to the runtime layer; the
 * bundled store is a jar-resource zip and treated as immutable.
 *
 * <p>The block listing is the union of both layers' keys (de-duped, runtime
 * takes precedence) so {@code /tessera debug rebake} can invalidate
 * bundled-only blocks; subsequent bakes write the override into runtime.
 */
public final class LayeredHeadsStore implements HeadsStore {

    private final HeadsStore bundled; // may be null
    private final HeadsStore runtime;

    public LayeredHeadsStore(HeadsStore bundled, HeadsStore runtime) {
        if (runtime == null) throw new IllegalArgumentException("runtime store is required");
        this.bundled = bundled;
        this.runtime = runtime;
    }

    public HeadsStore runtimeLayer() { return runtime; }
    public HeadsStore bundledLayer() { return bundled; }

    @Override
    public Optional<TsraFormat.Manifest> manifest() {
        Optional<TsraFormat.Manifest> r = runtime.manifest();
        if (r.isPresent()) return r;
        return bundled != null ? bundled.manifest() : Optional.empty();
    }

    @Override
    public Collection<BakeKey> listBlocks() {
        Set<BakeKey> out = new LinkedHashSet<>(runtime.listBlocks());
        if (bundled != null) out.addAll(bundled.listBlocks());
        return out;
    }

    @Override
    public Optional<TsraFormat.Block> readBlock(BakeKey key) {
        Optional<TsraFormat.Block> r = runtime.readBlock(key);
        if (r.isPresent()) return r;
        return bundled != null ? bundled.readBlock(key) : Optional.empty();
    }

    @Override
    public Optional<TsraFormat.Skin> readSkin(String hash) {
        Optional<TsraFormat.Skin> r = runtime.readSkin(hash);
        if (r.isPresent()) return r;
        return bundled != null ? bundled.readSkin(hash) : Optional.empty();
    }

    @Override public boolean isWritable() { return runtime.isWritable(); }

    @Override public void writeManifest(TsraFormat.Manifest m) { runtime.writeManifest(m); }
    @Override public void writeBlock(TsraFormat.Block b) { runtime.writeBlock(b); }
    @Override public void writeSkin(TsraFormat.Skin s) { runtime.writeSkin(s); }
    @Override public void removeBlock(BlockKey b) { runtime.removeBlock(b); }
    @Override public void clearBlocks() { runtime.clearBlocks(); }

    @Override
    public void close() {
        try { runtime.close(); } finally {
            if (bundled != null) bundled.close();
        }
    }
}
