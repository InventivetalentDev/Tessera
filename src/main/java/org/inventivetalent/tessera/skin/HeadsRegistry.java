package org.inventivetalent.tessera.skin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.inventivetalent.tessera.assets.model.ModelResolver;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.skin.bake.BlockBaker;
import org.inventivetalent.tessera.skin.store.HeadsStore;
import org.inventivetalent.tessera.skin.store.TsraFormat;
import org.joml.Quaternionf;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory index over a {@link HeadsStore}: maps
 * {@code (BakeKey, ChunkCoord)} → {@link Entry} (skin payload).
 *
 * <p>The registry holds only the cheap shape of the catalog eagerly — for
 * every block, a chunk → skin-hash map plus a tiny variant-rotation table.
 * The heavy {@code value}/{@code signature}/{@code mineskinUuid} blobs (a few
 * KB each) live on disk in {@code .tsra} files and stream in through a
 * Caffeine cache the first time a {@link #get} call resolves them. A bundled
 * 1.4k-skin catalog used to weigh ~3 MB resident; this design keeps the
 * baseline footprint under 200 KB and lets the cache decide what to keep
 * warm based on access patterns.
 *
 * <p>The store is layered (bundled-zip + runtime-folder, runtime shadows
 * bundled — see {@code LayeredHeadsStore}). All writes go to the runtime
 * layer; the bundled zip ships in the jar and is treated as immutable.
 */
public final class HeadsRegistry {

    public record Entry(String skinHash, String textureValue, String textureSignature, String mineskinUuid) {}

    private final Logger logger;
    private final int gridN;
    private final String version;
    private final HeadsStore store;
    // Lightweight projection of the catalog: chunk → hash + variants. Cheap
    // to keep in memory even for thousands of blocks (~100 bytes per block).
    private final Map<BakeKey, Map<ChunkCoord, String>> blockHashes = new ConcurrentHashMap<>();
    private final Map<BlockKey, Map<String, ModelResolver.VariantRotation>> variantRotations = new ConcurrentHashMap<>();
    // Add-only: blocks are never removed on invalidate so tab-completion sees a superset,
    // which is fine — invalidated blocks get re-baked on next use.
    private final Set<BlockKey> knownBlocks = ConcurrentHashMap.newKeySet();
    // Caffeine cache of skin payloads keyed by hash. Loader hits the store
    // on miss. Dedup is automatic: a uniform stone block at gridN=4 has 64
    // chunks pointing at ~3 unique hashes, so 64 get() calls trigger at
    // most 3 loader invocations.
    private final Cache<String, Entry> skinCache;

    private HeadsRegistry(Logger logger, int gridN, String version, HeadsStore store,
                          int cacheCapacity) {
        this.logger = logger;
        this.gridN = gridN;
        this.version = version;
        this.store = store;
        this.skinCache = Caffeine.newBuilder()
                .maximumSize(Math.max(64, cacheCapacity))
                .build();
    }

    /**
     * Construct a registry without a backing store. Used by tests that
     * register Entries directly and read them back without touching disk;
     * the in-memory Caffeine cache absorbs both sides of the round-trip.
     */
    public static HeadsRegistry empty(Logger logger, int gridN, String version) {
        return new HeadsRegistry(logger, gridN, version, NullStore.INSTANCE, 256);
    }

    /**
     * Construct a registry over {@code store}, populating the hash index
     * from every block file in the store. Skin payloads are <i>not</i>
     * loaded — they're streamed on demand through the Caffeine cache.
     */
    public static HeadsRegistry loadFrom(Logger logger, HeadsStore store,
                                         int gridN, String version, int cacheCapacity) {
        HeadsRegistry reg = new HeadsRegistry(logger, gridN, version, store, cacheCapacity);
        reg.populateIndex();
        return reg;
    }

    private void populateIndex() {
        Collection<BakeKey> keys = store.listBlocks();
        int loaded = 0;
        for (BakeKey key : keys) {
            Optional<TsraFormat.Block> opt = store.readBlock(key);
            if (opt.isEmpty()) continue;
            TsraFormat.Block b = opt.get();
            if (b.chunkHashes().isEmpty()) continue;
            blockHashes.put(key, Map.copyOf(b.chunkHashes()));
            knownBlocks.add(key.block());
            if (!b.variants().isEmpty()) {
                variantRotations.put(key.block(), Map.copyOf(b.variants()));
            }
            loaded++;
        }
        logger.info("[heads-registry] indexed " + loaded + " block(s) from store"
                + (store.manifest().isPresent()
                ? " (gridN=" + store.manifest().get().gridN()
                  + ", mcVersion=" + store.manifest().get().mcVersion() + ")"
                : ""));
    }

    public int gridN() { return gridN; }
    public String version() { return version; }

    public boolean has(BakeKey key) {
        Map<ChunkCoord, String> per = blockHashes.get(key);
        return per != null && !per.isEmpty();
    }

    /** Untinted convenience overload — equivalent to {@code has(BakeKey.untinted(key))}. */
    public boolean has(BlockKey key) {
        return has(BakeKey.untinted(key));
    }

    public Optional<Entry> get(BakeKey key, ChunkCoord coord) {
        Map<ChunkCoord, String> per = blockHashes.get(key);
        if (per == null) return Optional.empty();
        String hash = per.get(coord);
        if (hash == null) return Optional.empty();
        return Optional.ofNullable(loadByHash(hash));
    }

    /**
     * Resolve every chunk's skin in one shot, populating the Caffeine cache
     * with any payloads that miss. The returned map is a fresh snapshot —
     * mutating it has no effect on the registry. Entries whose payload
     * lookup fails are omitted so callers see a consistent view (no half-
     * loaded chunks).
     */
    public Map<ChunkCoord, Entry> chunksFor(BakeKey key) {
        Map<ChunkCoord, String> per = blockHashes.get(key);
        if (per == null) return Collections.emptyMap();
        Map<ChunkCoord, Entry> out = new LinkedHashMap<>(Math.max(8, per.size() * 2));
        for (Map.Entry<ChunkCoord, String> e : per.entrySet()) {
            Entry entry = loadByHash(e.getValue());
            if (entry != null) out.put(e.getKey(), entry);
        }
        return out;
    }

    /** Untinted convenience overload. */
    public Map<ChunkCoord, Entry> chunksFor(BlockKey key) {
        return chunksFor(BakeKey.untinted(key));
    }

    /** Look up a previously-registered skin by its content hash, or null. */
    public Entry findByHash(String hash) {
        return loadByHash(hash);
    }

    /**
     * Pre-load every skin payload for {@code key} into the cache without
     * resolving full Entries. Used by the "preload on look" path so a
     * subsequent {@link #get} on the mining hot-path doesn't hit disk.
     * Returns the number of unique hashes warmed.
     */
    public int warm(BakeKey key) {
        Map<ChunkCoord, String> per = blockHashes.get(key);
        if (per == null) return 0;
        int n = 0;
        // Dedup by hash so a uniform block doesn't trigger 64 redundant
        // loads — Caffeine.get is idempotent but we still want to count
        // unique payloads.
        Set<String> seen = new java.util.HashSet<>(per.values());
        for (String hash : seen) {
            if (skinCache.getIfPresent(hash) == null) {
                Entry e = loadFromStore(hash);
                if (e != null) {
                    skinCache.put(hash, e);
                    n++;
                }
            }
        }
        return n;
    }

    private Entry loadByHash(String hash) {
        Entry cached = skinCache.getIfPresent(hash);
        if (cached != null) return cached;
        Entry loaded = loadFromStore(hash);
        if (loaded != null) skinCache.put(hash, loaded);
        return loaded;
    }

    private Entry loadFromStore(String hash) {
        return store.readSkin(hash).map(TsraFormat.Skin::toEntry).orElse(null);
    }

    /**
     * Register chunks for {@code key} discovered at runtime by
     * {@link BlockBaker}. Replaces any existing entry for the same key. The
     * payload Entries are written through to the store (if writable) so a
     * restart finds them again, then prewarmed into the cache so the
     * immediately-following spawn doesn't pay a disk round-trip.
     */
    public void register(BakeKey key, Map<ChunkCoord, Entry> chunks) {
        register(key, chunks, Collections.emptyMap());
    }

    public void register(BakeKey key, Map<ChunkCoord, Entry> chunks,
                         Map<String, ModelResolver.VariantRotation> variants) {
        Map<ChunkCoord, String> chunkHashes = new LinkedHashMap<>(Math.max(8, chunks.size() * 2));
        for (Map.Entry<ChunkCoord, Entry> e : chunks.entrySet()) {
            chunkHashes.put(e.getKey(), e.getValue().skinHash());
            skinCache.put(e.getValue().skinHash(), e.getValue());
        }
        blockHashes.put(key, Map.copyOf(chunkHashes));
        knownBlocks.add(key.block());
        Map<String, ModelResolver.VariantRotation> variantsCopy =
                (variants == null || variants.isEmpty()) ? Collections.emptyMap() : Map.copyOf(variants);
        if (!variantsCopy.isEmpty()) variantRotations.put(key.block(), variantsCopy);

        if (store.isWritable()) {
            try {
                // Skins first so a partially-flushed write still resolves to
                // valid payloads — the block file is the index, so writing
                // it last means a kill mid-bake leaves orphan skins (dead
                // weight) instead of dangling references (broken catalog).
                for (Entry e : chunks.values()) {
                    store.writeSkin(TsraFormat.Skin.from(e));
                }
                store.writeBlock(new TsraFormat.Block(key, chunkHashes, variantsCopy));
            } catch (RuntimeException re) {
                logger.warning("[heads-registry] persistence write failed for " + key + ": " + re.getMessage());
            }
        }
    }

    /**
     * Look up the world-space rotation for a specific blockstate variant of
     * {@code key}. See the prior implementation's contract: identity is
     * returned for unknown keys / variants so blocks predating the variant
     * map render in canonical orientation.
     */
    public Quaternionf rotationFor(BlockKey key, String variantKey) {
        Map<String, ModelResolver.VariantRotation> variants = variantRotations.get(key);
        if (variants == null || variantKey == null) return new Quaternionf();
        ModelResolver.VariantRotation rot = variants.get(variantKey);
        return rot != null ? rot.toQuat() : new Quaternionf();
    }

    /** Live view of every block currently registered (bundled + runtime, across all tints). */
    public Set<BlockKey> knownBlockKeys() {
        return Collections.unmodifiableSet(knownBlocks);
    }

    public Map<String, ModelResolver.VariantRotation> variantsFor(BlockKey key) {
        Map<String, ModelResolver.VariantRotation> variants = variantRotations.get(key);
        return variants == null ? Collections.emptyMap() : variants;
    }

    /**
     * Forget every runtime registration for {@code block} (untinted plus
     * every tinted variant) so the next request re-runs the bake. Bundled
     * entries are cleared from the in-memory index too; the bundled file
     * itself is jar-resource immutable and re-launching the plugin
     * reloads them.
     */
    public boolean invalidate(BlockKey block) {
        variantRotations.remove(block);
        boolean removed = blockHashes.keySet().removeIf(k -> k.block().equals(block));
        if (store.isWritable()) {
            try { store.removeBlock(block); }
            catch (RuntimeException re) { logger.warning("[heads-registry] store remove failed: " + re.getMessage()); }
        }
        return removed;
    }

    /**
     * Forget every runtime-registered (and bundled) entry. Used by
     * {@code /tessera debug tilerot} since changing tile rotation needs
     * fresh PNGs uploaded — the dedup hash is computed pre-rotation, so
     * existing cached entries would otherwise mask the change.
     */
    public int invalidateAll() {
        int n = blockHashes.size();
        blockHashes.clear();
        variantRotations.clear();
        // skinCache stays — its only consumer is findByHash, which is
        // bypassed when TileRotations.consumeStale is true.
        if (store.isWritable()) {
            try { store.clearBlocks(); }
            catch (RuntimeException re) { logger.warning("[heads-registry] store clear failed: " + re.getMessage()); }
        }
        return n;
    }

    /**
     * Build a runtime {@link HeadSkin} from a registry entry. The skin has no
     * tile bitmaps (those were baked away into the MineSkin texture) and no
     * chunk bookkeeping — it exists purely to feed
     * {@code HeadItemFactory#build} on the spawn path.
     */
    public static HeadSkin toHeadSkin(Entry e) {
        HeadSkin h = new HeadSkin(UUID.randomUUID(), e.skinHash(), Collections.emptyMap());
        h.texture(e.textureValue(), e.textureSignature(), e.mineskinUuid());
        h.state(SkinState.COMPLETED);
        return h;
    }

    /** Internal no-op store for {@link #empty(Logger, int, String)} / tests that don't need persistence. */
    private static final class NullStore implements HeadsStore {
        static final NullStore INSTANCE = new NullStore();
        @Override public Optional<TsraFormat.Manifest> manifest() { return Optional.empty(); }
        @Override public Collection<BakeKey> listBlocks() { return Collections.emptyList(); }
        @Override public Optional<TsraFormat.Block> readBlock(BakeKey key) { return Optional.empty(); }
        @Override public Optional<TsraFormat.Skin> readSkin(String hash) { return Optional.empty(); }
        @Override public boolean isWritable() { return false; }
        @Override public void close() {}
    }
}
