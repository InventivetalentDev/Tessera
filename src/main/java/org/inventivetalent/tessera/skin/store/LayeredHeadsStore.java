package org.inventivetalent.tessera.skin.store;

import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Stack of {@link HeadsStore}s composed of one writable runtime layer at
 * the top and any number of read-only layers underneath. Reads consult
 * layers in priority order — writable first, then each read-only layer in
 * the order given — so a re-baked block always shadows older copies.
 * Writes go to the writable layer; the read-only layers are typically a
 * jar-resource bundle plus optional addon {@code .ztsra} packs that the
 * server admin drops into the plugin folder.
 *
 * <p>{@link #listBlocks()} is the union across all layers, de-duped by
 * {@link BakeKey} — that's what {@code /tessera debug rebake} walks when a
 * bundled or addon-only block needs to be invalidated; the next bake
 * writes the override into the writable layer.
 */
public final class LayeredHeadsStore implements HeadsStore {

    private final HeadsStore writable;
    // Composed read-only stack — recomputed on reloadAddons. Volatile so
    // readers see a complete swap atomically.
    private volatile List<HeadsStore> readOnly;

    // Inputs preserved for reloadAddons. All null when constructed via the
    // legacy ctor — that constructor doesn't track which read-only layer
    // is "the bundled one", so it can't safely re-scan and recompose.
    private final HeadsStore bundled;
    private final Path addonsDir;
    private final int addonsGridN;

    /**
     * Build a layered store. {@code writable} accepts every mutation and
     * sits at the top of the read priority order. {@code readOnly} layers
     * are consulted in iteration order on a miss; a {@code null} or empty
     * list is fine (means "no bundled or addon layers").
     *
     * <p>Stores built with this constructor cannot {@link #reloadAddons};
     * for runtime use prefer
     * {@link #LayeredHeadsStore(HeadsStore, List, HeadsStore, Path, int)}.
     */
    public LayeredHeadsStore(HeadsStore writable, List<? extends HeadsStore> readOnly) {
        if (writable == null) throw new IllegalArgumentException("writable store is required");
        this.writable = writable;
        this.readOnly = readOnly == null ? List.of() : List.copyOf(readOnly);
        this.bundled = null;
        this.addonsDir = null;
        this.addonsGridN = 0;
    }

    /**
     * Reload-capable constructor. Tracks the bundled layer and the addons
     * directory separately so {@link #reloadAddons} can re-scan the
     * directory without disturbing the bundled or writable layers.
     *
     * @param initialAddons stores wrapping {@code .ztsra} packs in
     *                      {@code addonsDir}, in lookup priority order
     * @param bundled       the jar-resource pack, or {@code null} if absent
     * @param addonsDir     where {@code reloadAddons} re-runs
     *                      {@code AddonPackLoader.load}
     * @param addonsGridN   passes through to {@code AddonPackLoader.load}
     */
    public LayeredHeadsStore(HeadsStore writable,
                             List<? extends HeadsStore> initialAddons,
                             HeadsStore bundled,
                             Path addonsDir,
                             int addonsGridN) {
        if (writable == null) throw new IllegalArgumentException("writable store is required");
        if (addonsDir == null) throw new IllegalArgumentException("addonsDir is required");
        this.writable = writable;
        this.bundled = bundled;
        this.addonsDir = addonsDir;
        this.addonsGridN = addonsGridN;
        this.readOnly = composeReadOnly(initialAddons);
    }

    private List<HeadsStore> composeReadOnly(List<? extends HeadsStore> addons) {
        List<HeadsStore> out = new ArrayList<>();
        if (addons != null) out.addAll(addons);
        if (bundled != null) out.add(bundled);
        return List.copyOf(out);
    }

    public HeadsStore writableLayer() { return writable; }
    public List<HeadsStore> readOnlyLayers() { return readOnly; }

    /**
     * Re-scan the configured addons directory and replace the addons
     * layer. Bundled and writable layers are preserved. Existing addon
     * stores being dropped are closed best-effort.
     *
     * @return the number of addon packs now active
     * @throws IllegalStateException if this store was built with the
     *                               legacy constructor that doesn't track
     *                               an addons directory
     */
    public synchronized int reloadAddons(Logger logger) {
        if (addonsDir == null) {
            throw new IllegalStateException(
                    "LayeredHeadsStore was not constructed with reload-capable inputs");
        }
        List<AddonPackLoader.LoadedAddon> loaded =
                AddonPackLoader.load(logger, addonsDir, addonsGridN);
        List<HeadsStore> newAddons = new ArrayList<>(loaded.size());
        for (AddonPackLoader.LoadedAddon a : loaded) newAddons.add(a.store());

        List<HeadsStore> oldReadOnly = this.readOnly;
        this.readOnly = composeReadOnly(newAddons);

        // Close stale addon stores we just dropped. Skip the bundled one
        // (it's still in the new list, identity-equal).
        for (HeadsStore old : oldReadOnly) {
            if (old == bundled) continue;
            try {
                old.close();
            } catch (RuntimeException re) {
                logger.warning("[heads-addons] failed to close stale store: " + re.getMessage());
            }
        }
        return loaded.size();
    }

    @Override
    public Optional<TsraFormat.Manifest> manifest() {
        Optional<TsraFormat.Manifest> w = writable.manifest();
        if (w.isPresent()) return w;
        for (HeadsStore layer : readOnly) {
            Optional<TsraFormat.Manifest> m = layer.manifest();
            if (m.isPresent()) return m;
        }
        return Optional.empty();
    }

    @Override
    public Collection<BakeKey> listBlocks() {
        Set<BakeKey> out = new LinkedHashSet<>(writable.listBlocks());
        for (HeadsStore layer : readOnly) out.addAll(layer.listBlocks());
        return out;
    }

    @Override
    public Optional<TsraFormat.Block> readBlock(BakeKey key) {
        Optional<TsraFormat.Block> w = writable.readBlock(key);
        if (w.isPresent()) return w;
        for (HeadsStore layer : readOnly) {
            Optional<TsraFormat.Block> b = layer.readBlock(key);
            if (b.isPresent()) return b;
        }
        return Optional.empty();
    }

    @Override
    public Optional<TsraFormat.Skin> readSkin(String hash) {
        Optional<TsraFormat.Skin> w = writable.readSkin(hash);
        if (w.isPresent()) return w;
        for (HeadsStore layer : readOnly) {
            Optional<TsraFormat.Skin> s = layer.readSkin(hash);
            if (s.isPresent()) return s;
        }
        return Optional.empty();
    }

    @Override public boolean isWritable() { return writable.isWritable(); }

    @Override public void writeManifest(TsraFormat.Manifest m) { writable.writeManifest(m); }
    @Override public void writeBlock(TsraFormat.Block b) { writable.writeBlock(b); }
    @Override public void writeSkin(TsraFormat.Skin s) { writable.writeSkin(s); }
    @Override public void removeBlock(BlockKey b) { writable.removeBlock(b); }
    @Override public void clearBlocks() { writable.clearBlocks(); }

    @Override
    public void close() {
        List<Throwable> errors = new ArrayList<>();
        try { writable.close(); } catch (RuntimeException re) { errors.add(re); }
        for (HeadsStore layer : readOnly) {
            try { layer.close(); } catch (RuntimeException re) { errors.add(re); }
        }
        if (!errors.isEmpty()) {
            // Propagate the first error; the rest were already attempted so
            // their state is consistent regardless. Wrapping in a runtime so
            // close() stays interface-compatible (no IOException declared).
            Throwable first = errors.get(0);
            if (first instanceof RuntimeException re) throw re;
            throw new RuntimeException(first);
        }
    }
}
