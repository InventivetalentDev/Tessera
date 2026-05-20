package org.inventivetalent.tessera.skin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.inventivetalent.tessera.assets.model.BlockModel;
import org.inventivetalent.tessera.assets.model.ModelResolver;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.FaceDir;
import org.inventivetalent.tessera.skin.bake.BlockBaker;
import org.inventivetalent.tessera.skin.store.HeadsStore;
import org.inventivetalent.tessera.skin.store.TsraFormat;
import org.joml.Quaternionf;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory index over a {@link HeadsStore}: maps
 * {@code (BakeKey, shapeKey, ChunkCoord)} → {@link ChunkEntry} (skin payload
 * + outward-face mask).
 *
 * <p>The registry holds only the cheap shape of the catalog eagerly — for
 * every block, a per-shape chunk → (hash, outward-mask) map plus a variant
 * → (shape, rotation) table. The heavy
 * {@code value}/{@code signature}/{@code mineskinUuid} blobs live on disk
 * in {@code .tsra} files and stream in through a Caffeine cache the first
 * time a {@link #chunksFor} call resolves them.
 *
 * <p>Multi-shape support: blocks like stairs have multiple model files
 * referenced by their blockstate (straight, inner-corner, outer-corner).
 * Each is stored as a separate shape under its model path. Cube blocks
 * have a single shape under either the canonical model path or
 * {@link BlockModel#DEFAULT_SHAPE_KEY}. Spawn-time picks the right shape
 * via {@link #variantBindingFor}.
 *
 * <p>The store is layered (bundled-zip + runtime-folder, runtime shadows
 * bundled — see {@code LayeredHeadsStore}). All writes go to the runtime
 * layer; the bundled zip ships in the jar and is treated as immutable.
 */
public final class HeadsRegistry {

    /** Skin payload entry — content hash + MineSkin texture triple. */
    public record Entry(String skinHash, String textureValue, String textureSignature, String mineskinUuid) {}

    /**
     * Per-coord chunk record at runtime: resolved skin payload + the
     * outward-face set baked at build time. Spawn reads outward faces from
     * here directly so {@code FakeBlockFactory} stays shape-agnostic.
     */
    public record ChunkEntry(Entry skin, EnumSet<FaceDir> outwardFaces) {}

    /** Lightweight in-memory chunk record: just hash + outward mask. */
    private record StoredChunk(String skinHash, byte outwardMask) {}

    /** Re-export for callers (BlockBreakListener et al.) that resolve a variant to a shape + rotation. */
    public record ShapeVariantBinding(String shapeKey, ModelResolver.VariantRotation rotation) {}

    private final Logger logger;
    private final int gridN;
    private final String version;
    private final HeadsStore store;
    // Per BakeKey: shapeKey → coord → stored chunk. Per BlockKey: variant → binding;
    // and a defaultShapeKey lookup so callers without a variant get something usable.
    private final Map<BakeKey, Map<String, Map<ChunkCoord, StoredChunk>>> blockShapes = new ConcurrentHashMap<>();
    private final Map<BakeKey, String> defaultShape = new ConcurrentHashMap<>();
    private final Map<BlockKey, Map<String, ShapeVariantBinding>> variantBindings = new ConcurrentHashMap<>();
    private final Set<BlockKey> knownBlocks = ConcurrentHashMap.newKeySet();
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

    public static HeadsRegistry empty(Logger logger, int gridN, String version) {
        return new HeadsRegistry(logger, gridN, version, NullStore.INSTANCE, 256);
    }

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
            if (b.shapes().isEmpty()) continue;
            ingestBlock(b);
            loaded++;
        }
        logger.info("[heads-registry] indexed " + loaded + " block(s) from store"
                + (store.manifest().isPresent()
                ? " (gridN=" + store.manifest().get().gridN()
                  + ", mcVersion=" + store.manifest().get().mcVersion() + ")"
                : ""));
    }

    private void ingestBlock(TsraFormat.Block b) {
        LinkedHashMap<String, Map<ChunkCoord, StoredChunk>> shapeIndex = new LinkedHashMap<>();
        for (Map.Entry<String, TsraFormat.Shape> se : b.shapes().entrySet()) {
            LinkedHashMap<ChunkCoord, StoredChunk> chunkIndex = new LinkedHashMap<>(se.getValue().chunks().size() * 2);
            for (Map.Entry<ChunkCoord, TsraFormat.ChunkRecord> ce : se.getValue().chunks().entrySet()) {
                chunkIndex.put(ce.getKey(), new StoredChunk(ce.getValue().hash(), ce.getValue().outwardMask()));
            }
            shapeIndex.put(se.getKey(), Collections.unmodifiableMap(chunkIndex));
        }
        blockShapes.put(b.key(), Collections.unmodifiableMap(shapeIndex));
        defaultShape.put(b.key(), pickDefaultShapeKey(shapeIndex));
        knownBlocks.add(b.key().block());
        if (!b.variants().isEmpty()) {
            LinkedHashMap<String, ShapeVariantBinding> vCopy = new LinkedHashMap<>(b.variants().size() * 2);
            for (Map.Entry<String, TsraFormat.ShapeVariantBinding> ve : b.variants().entrySet()) {
                vCopy.put(ve.getKey(), new ShapeVariantBinding(
                        ve.getValue().shapeKey(), ve.getValue().rotation()));
            }
            variantBindings.put(b.key().block(), Collections.unmodifiableMap(vCopy));
        }
    }

    private static String pickDefaultShapeKey(Map<String, Map<ChunkCoord, StoredChunk>> shapeIndex) {
        if (shapeIndex.containsKey(BlockModel.DEFAULT_SHAPE_KEY)) return BlockModel.DEFAULT_SHAPE_KEY;
        return shapeIndex.keySet().iterator().next();
    }

    /**
     * Re-walk the backing store and merge any newly visible blocks into
     * the in-memory index. Idempotent.
     */
    public synchronized int reindex() {
        int before = blockShapes.size();
        populateIndex();
        return blockShapes.size() - before;
    }

    public int gridN() { return gridN; }
    public String version() { return version; }

    public boolean has(BakeKey key) {
        Map<String, Map<ChunkCoord, StoredChunk>> shapes = blockShapes.get(key);
        if (shapes == null || shapes.isEmpty()) return false;
        for (Map<ChunkCoord, StoredChunk> s : shapes.values()) if (!s.isEmpty()) return true;
        return false;
    }

    public boolean has(BlockKey key) { return has(BakeKey.untinted(key)); }

    /**
     * Default shape key for this BakeKey (the canonical model the
     * blockstate's no-rotation variant references, or
     * {@link BlockModel#DEFAULT_SHAPE_KEY} for v1-migrated records). Used
     * as a fallback when the runtime can't match a variant.
     */
    public String defaultShapeFor(BakeKey key) {
        String s = defaultShape.get(key);
        return s != null ? s : BlockModel.DEFAULT_SHAPE_KEY;
    }

    /**
     * Resolve every chunk's skin in one shot for the given shape, populating
     * the Caffeine cache with any payloads that miss. Returns an empty map
     * when the BakeKey is unregistered or {@code shapeKey} resolves to no
     * chunks.
     */
    public Map<ChunkCoord, ChunkEntry> chunksFor(BakeKey key, String shapeKey) {
        Map<String, Map<ChunkCoord, StoredChunk>> shapes = blockShapes.get(key);
        if (shapes == null) return Collections.emptyMap();
        Map<ChunkCoord, StoredChunk> stored = shapes.get(shapeKey);
        if (stored == null) stored = shapes.get(defaultShape.get(key));
        if (stored == null || stored.isEmpty()) return Collections.emptyMap();
        LinkedHashMap<ChunkCoord, ChunkEntry> out = new LinkedHashMap<>(stored.size() * 2);
        for (Map.Entry<ChunkCoord, StoredChunk> e : stored.entrySet()) {
            Entry skin = loadByHash(e.getValue().skinHash());
            if (skin == null) continue;
            out.put(e.getKey(), new ChunkEntry(skin, decode(e.getValue().outwardMask())));
        }
        return out;
    }

    /**
     * Convenience: chunks for {@link #defaultShapeFor default shape} of
     * {@code key}.
     */
    public Map<ChunkCoord, ChunkEntry> chunksFor(BakeKey key) {
        return chunksFor(key, defaultShapeFor(key));
    }

    public Map<ChunkCoord, ChunkEntry> chunksFor(BlockKey key) {
        return chunksFor(BakeKey.untinted(key));
    }

    /** Look up a previously-registered skin by its content hash, or null. */
    public Entry findByHash(String hash) {
        return loadByHash(hash);
    }

    /**
     * Legacy single-coord lookup: returns the skin {@link Entry} for the
     * default shape's chunk at {@code coord}, or empty if unregistered.
     * Multi-shape callers should use {@link #chunksFor(BakeKey, String)}
     * and read {@link ChunkEntry#skin()} per coord.
     */
    public Optional<Entry> get(BakeKey key, ChunkCoord coord) {
        Map<String, Map<ChunkCoord, StoredChunk>> shapes = blockShapes.get(key);
        if (shapes == null) return Optional.empty();
        Map<ChunkCoord, StoredChunk> stored = shapes.get(defaultShapeFor(key));
        if (stored == null) return Optional.empty();
        StoredChunk sc = stored.get(coord);
        if (sc == null) return Optional.empty();
        return Optional.ofNullable(loadByHash(sc.skinHash()));
    }

    /**
     * Pre-load every skin payload for {@code key}'s default shape into the
     * cache without resolving full Entries. Used by the "preload on look"
     * path so a subsequent {@link #chunksFor} on the mining hot-path
     * doesn't hit disk.
     */
    public int warm(BakeKey key) {
        Map<String, Map<ChunkCoord, StoredChunk>> shapes = blockShapes.get(key);
        if (shapes == null) return 0;
        int n = 0;
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Map<ChunkCoord, StoredChunk> stored : shapes.values()) {
            for (StoredChunk sc : stored.values()) seen.add(sc.skinHash());
        }
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

    private static EnumSet<FaceDir> decode(byte mask) {
        EnumSet<FaceDir> out = EnumSet.noneOf(FaceDir.class);
        for (FaceDir d : FaceDir.values()) {
            if ((mask & (1 << d.ordinal())) != 0) out.add(d);
        }
        return out;
    }

    /**
     * Register the result of a bake. {@code shapes} carries the per-shape
     * chunk index ({@code shapeKey → coord → ChunkEntry}); {@code variants}
     * carries the variant → (shape, rotation) bindings; {@code defaultShapeKey}
     * is the fallback used when a variant key doesn't match anything.
     */
    public void register(BakeKey key,
                          String defaultShapeKey,
                          Map<String, Map<ChunkCoord, ChunkEntry>> shapes,
                          Map<String, ShapeVariantBinding> variants) {
        if (shapes.isEmpty()) return;
        // Build the in-memory index + the on-disk TsraFormat.Block in one pass.
        LinkedHashMap<String, Map<ChunkCoord, StoredChunk>> memShapes = new LinkedHashMap<>();
        LinkedHashMap<String, TsraFormat.Shape> diskShapes = new LinkedHashMap<>();
        for (Map.Entry<String, Map<ChunkCoord, ChunkEntry>> se : shapes.entrySet()) {
            LinkedHashMap<ChunkCoord, StoredChunk> memChunks = new LinkedHashMap<>(se.getValue().size() * 2);
            LinkedHashMap<ChunkCoord, TsraFormat.ChunkRecord> diskChunks = new LinkedHashMap<>(se.getValue().size() * 2);
            for (Map.Entry<ChunkCoord, ChunkEntry> ce : se.getValue().entrySet()) {
                ChunkEntry ch = ce.getValue();
                byte mask = TsraFormat.ChunkRecord.mask(ch.outwardFaces());
                memChunks.put(ce.getKey(), new StoredChunk(ch.skin().skinHash(), mask));
                diskChunks.put(ce.getKey(), new TsraFormat.ChunkRecord(ch.skin().skinHash(), mask));
                skinCache.put(ch.skin().skinHash(), ch.skin());
            }
            memShapes.put(se.getKey(), Collections.unmodifiableMap(memChunks));
            diskShapes.put(se.getKey(), new TsraFormat.Shape(diskChunks));
        }
        blockShapes.put(key, Collections.unmodifiableMap(memShapes));
        defaultShape.put(key, defaultShapeKey != null ? defaultShapeKey : pickDefaultShapeKey(memShapes));
        knownBlocks.add(key.block());
        Map<String, ShapeVariantBinding> varsCopy =
                (variants == null || variants.isEmpty()) ? Collections.emptyMap() : Map.copyOf(variants);
        if (!varsCopy.isEmpty()) variantBindings.put(key.block(), varsCopy);

        if (store.isWritable()) {
            try {
                // Skins first so a partially-flushed write still resolves to
                // valid payloads — the block file is the index, so writing
                // it last means a kill mid-bake leaves orphan skins (dead
                // weight) instead of dangling references (broken catalog).
                for (Map<ChunkCoord, ChunkEntry> sh : shapes.values()) {
                    for (ChunkEntry ce : sh.values()) {
                        store.writeSkin(TsraFormat.Skin.from(ce.skin()));
                    }
                }
                LinkedHashMap<String, TsraFormat.ShapeVariantBinding> diskVariants =
                        new LinkedHashMap<>(varsCopy.size() * 2);
                for (Map.Entry<String, ShapeVariantBinding> ve : varsCopy.entrySet()) {
                    diskVariants.put(ve.getKey(), new TsraFormat.ShapeVariantBinding(
                            ve.getValue().shapeKey(), ve.getValue().rotation()));
                }
                store.writeBlock(new TsraFormat.Block(key, diskShapes, diskVariants));
            } catch (RuntimeException re) {
                logger.warning("[heads-registry] persistence write failed for " + key + ": " + re.getMessage());
            }
        }
    }

    /**
     * Single-shape register convenience used by the runtime baker. All
     * variants bind to the same shape; the shape key is
     * {@link BlockModel#DEFAULT_SHAPE_KEY}.
     */
    public void registerSingleShape(BakeKey key, Map<ChunkCoord, ChunkEntry> chunks,
                                     Map<String, ModelResolver.VariantRotation> variants) {
        LinkedHashMap<String, Map<ChunkCoord, ChunkEntry>> shapes = new LinkedHashMap<>(1);
        shapes.put(BlockModel.DEFAULT_SHAPE_KEY, chunks);
        LinkedHashMap<String, ShapeVariantBinding> bound = new LinkedHashMap<>();
        if (variants != null) {
            for (Map.Entry<String, ModelResolver.VariantRotation> e : variants.entrySet()) {
                bound.put(e.getKey(), new ShapeVariantBinding(BlockModel.DEFAULT_SHAPE_KEY, e.getValue()));
            }
        }
        register(key, BlockModel.DEFAULT_SHAPE_KEY, shapes, bound);
    }

    /**
     * Legacy convenience used by tests + pre-multishape callers. Wraps the
     * skin {@link Entry} map in {@link ChunkEntry}s with outward-face masks
     * computed via {@link FaceDir#isOutwardAt} (cube assumption; correct for
     * every caller that uses this legacy entry-point).
     */
    public void register(BakeKey key, Map<ChunkCoord, Entry> chunks) {
        register(key, chunks, Collections.emptyMap());
    }

    public void register(BakeKey key, Map<ChunkCoord, Entry> chunks,
                          Map<String, ModelResolver.VariantRotation> variants) {
        LinkedHashMap<ChunkCoord, ChunkEntry> wrapped = new LinkedHashMap<>(chunks.size() * 2);
        for (Map.Entry<ChunkCoord, Entry> e : chunks.entrySet()) {
            ChunkCoord c = e.getKey();
            EnumSet<FaceDir> outward = EnumSet.noneOf(FaceDir.class);
            for (FaceDir d : FaceDir.values()) {
                if (d.isOutwardAt(c.x(), c.y(), c.z(), gridN)) outward.add(d);
            }
            wrapped.put(c, new ChunkEntry(e.getValue(), outward));
        }
        registerSingleShape(key, wrapped, variants);
    }

    /**
     * Resolve a variant key to its (shape, rotation) binding. Returns a
     * binding pointing at the default shape with identity rotation when no
     * variants are known or {@code variantKey} doesn't match.
     */
    public ShapeVariantBinding variantBindingFor(BlockKey key, String variantKey) {
        Map<String, ShapeVariantBinding> binds = variantBindings.get(key);
        if (binds != null && variantKey != null) {
            ShapeVariantBinding b = binds.get(variantKey);
            if (b != null) return b;
        }
        return new ShapeVariantBinding(BlockModel.DEFAULT_SHAPE_KEY,
                new ModelResolver.VariantRotation(0, 0));
    }

    /**
     * Backward-compat shim: look up the world-space rotation for a variant.
     * Identity if unknown.
     */
    public Quaternionf rotationFor(BlockKey key, String variantKey) {
        return variantBindingFor(key, variantKey).rotation().toQuat();
    }

    /** Live view of every block currently registered (bundled + runtime, across all tints). */
    public Set<BlockKey> knownBlockKeys() {
        return Collections.unmodifiableSet(knownBlocks);
    }

    /**
     * Variant bindings for the block. {@code VariantKey.pickMatching} runs
     * against this map's key set to narrow a BlockData to a known binding.
     */
    public Map<String, ShapeVariantBinding> variantsFor(BlockKey key) {
        Map<String, ShapeVariantBinding> binds = variantBindings.get(key);
        return binds == null ? Collections.emptyMap() : binds;
    }

    public boolean invalidate(BlockKey block) {
        variantBindings.remove(block);
        boolean removed = blockShapes.keySet().removeIf(k -> k.block().equals(block));
        defaultShape.keySet().removeIf(k -> k.block().equals(block));
        if (store.isWritable()) {
            try { store.removeBlock(block); }
            catch (RuntimeException re) { logger.warning("[heads-registry] store remove failed: " + re.getMessage()); }
        }
        return removed;
    }

    public int invalidateAll() {
        int n = blockShapes.size();
        blockShapes.clear();
        defaultShape.clear();
        variantBindings.clear();
        if (store.isWritable()) {
            try { store.clearBlocks(); }
            catch (RuntimeException re) { logger.warning("[heads-registry] store clear failed: " + re.getMessage()); }
        }
        return n;
    }

    /**
     * Build a runtime {@link HeadSkin} from a registry entry's skin payload.
     */
    public static HeadSkin toHeadSkin(Entry e) {
        HeadSkin h = new HeadSkin(HeadSkin.idFromHash(e.skinHash()), e.skinHash(), Collections.emptyMap());
        h.texture(e.textureValue(), e.textureSignature(), e.mineskinUuid());
        h.state(SkinState.COMPLETED);
        return h;
    }

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
