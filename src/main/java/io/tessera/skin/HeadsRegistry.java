package io.tessera.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.tessera.assets.model.ModelResolver;
import io.tessera.core.BakeKey;
import io.tessera.core.BlockKey;
import io.tessera.core.ChunkCoord;
import org.joml.Quaternionf;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Read-only lookup over the bundled {@code heads.json}: maps
 * {@code (BlockKey, ChunkCoord)} → {@link Entry} carrying a MineSkin texture
 * value/signature.
 *
 * <p>Built once at plugin startup. v1 doesn't upload at runtime — if a block
 * isn't in heads.json, callers (e.g. the BlockBreakListener) fall back to
 * vanilla animation. Server admins can extend the set by editing
 * {@code bake-blocks.txt} and re-running {@code ./gradlew tesseraBake}.
 *
 * <p>heads.json schema:
 * <pre>{@code
 * {
 *   "version": "1.21.4",
 *   "gridN": 4,
 *   "blocks": {
 *     "minecraft:stone": {
 *       "chunks": {
 *         "0,0,0": { "skinHash": "abc..." },
 *         "1,0,0": { "skinHash": "abc..." }
 *       },
 *       "variants": {
 *         "axis=y": { "x": 0, "y": 0 },
 *         "axis=x": { "x": 90, "y": 90 }
 *       }
 *     }
 *   },
 *   "skins": {
 *     "abc...": { "value": "ey...", "signature": "...", "mineskinUuid": "..." }
 *   }
 * }
 * }</pre>
 * The two-level structure (block→hash, hash→texture) means uniform blocks
 * inflate to ~1KB instead of ~50KB on disk. The legacy schema (chunk
 * coordinates as top-level keys under each block, no {@code chunks}/{@code variants}
 * wrapper) is also accepted for backward compat — see {@link #parseBlock}.
 *
 * <p>The optional {@code variants} map carries blockstate rotation hints
 * captured at bake time. Spawn-time lookup via {@link #rotationFor} returns
 * the quaternion that turns the canonical-variant baked textures into the
 * correct world orientation for the requested {@code BlockData} state.
 * Missing variants → identity (matches old heads.json files that pre-date
 * this feature).
 */
public final class HeadsRegistry {

    public record Entry(String skinHash, String textureValue, String textureSignature, String mineskinUuid) {}

    /**
     * Optional persistence sink for runtime-baked entries. The plugin wires
     * this to a JSON file under {@code plugins/Tessera/cache/} so registry
     * mutations survive restarts. Bundled-{@code heads.json} entries loaded
     * at startup do <i>not</i> flow through here — only later mutations from
     * {@link #register} / {@link #invalidate} / {@link #invalidateAll} do.
     */
    public interface Persistence {
        void save(BakeKey key, Map<ChunkCoord, Entry> chunks,
                  Map<String, ModelResolver.VariantRotation> variants);
        void remove(BlockKey block);
        void clear();
    }

    private final Logger logger;
    private final int gridN;
    private final String version;
    private volatile Persistence persistence;
    // Keyed by BakeKey so tinted runtime variants (different tintArgb of the
    // same block) get separate chunk maps. heads.json on disk only ever
    // contains untinted entries — they're loaded as BakeKey.untinted(...)
    // so the schema is unchanged.
    private final Map<BakeKey, Map<ChunkCoord, Entry>> blocks;
    // Variants are tint-independent (rotation depends on blockstate, not
    // biome), so this map stays keyed by BlockKey.
    private final Map<BlockKey, Map<String, ModelResolver.VariantRotation>> variantRotations;
    private final Map<String, Entry> hashIndex;

    private HeadsRegistry(Logger logger, int gridN, String version,
                          Map<BakeKey, Map<ChunkCoord, Entry>> blocks,
                          Map<BlockKey, Map<String, ModelResolver.VariantRotation>> variantRotations) {
        this.logger = logger;
        this.gridN = gridN;
        this.version = version;
        // Make blocks mutable so RuntimeBakeService can register on demand;
        // wrapped in synchronizedMap on read paths since BlockBreakListener
        // (main thread) and BlockBaker (async pool) both access it.
        this.blocks = new java.util.concurrent.ConcurrentHashMap<>(blocks);
        this.variantRotations = new java.util.concurrent.ConcurrentHashMap<>(variantRotations);
        this.hashIndex = new java.util.concurrent.ConcurrentHashMap<>();
        for (Map<ChunkCoord, Entry> per : blocks.values()) {
            for (Entry e : per.values()) hashIndex.putIfAbsent(e.skinHash(), e);
        }
    }

    public static HeadsRegistry empty(Logger logger, int gridN, String version) {
        return new HeadsRegistry(logger, gridN, version, Collections.emptyMap(), Collections.emptyMap());
    }

    /** Load from a classpath resource (e.g. {@code "/heads.json"}). Returns an empty registry on missing or invalid content. */
    public static HeadsRegistry loadFromClasspath(Logger logger, String resource, int defaultGridN, String defaultVersion) {
        try (InputStream in = HeadsRegistry.class.getResourceAsStream(resource)) {
            if (in == null) {
                logger.info("No bundled " + resource + " - HeadsRegistry is empty");
                return empty(logger, defaultGridN, defaultVersion);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(logger, json, defaultGridN, defaultVersion);
        } catch (IOException io) {
            logger.warning("Failed to read " + resource + ": " + io.getMessage());
            return empty(logger, defaultGridN, defaultVersion);
        }
    }

    public static HeadsRegistry loadFromFile(Logger logger, Path file, int defaultGridN, String defaultVersion) {
        if (!Files.isRegularFile(file)) {
            return empty(logger, defaultGridN, defaultVersion);
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return parse(logger, json, defaultGridN, defaultVersion);
        } catch (IOException io) {
            logger.warning("Failed to read " + file + ": " + io.getMessage());
            return empty(logger, defaultGridN, defaultVersion);
        }
    }

    private static HeadsRegistry parse(Logger logger, String json, int defaultGridN, String defaultVersion) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int gridN = root.has("gridN") ? root.get("gridN").getAsInt() : defaultGridN;
        String version = root.has("version") ? root.get("version").getAsString() : defaultVersion;

        Map<String, Entry> skinByHash = new HashMap<>();
        if (root.has("skins")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("skins").entrySet()) {
                JsonObject skin = e.getValue().getAsJsonObject();
                skinByHash.put(e.getKey(), new Entry(
                        e.getKey(),
                        skin.get("value").getAsString(),
                        skin.get("signature").getAsString(),
                        skin.has("mineskinUuid") ? skin.get("mineskinUuid").getAsString() : null));
            }
        }

        Map<BakeKey, Map<ChunkCoord, Entry>> blocks = new HashMap<>();
        Map<BlockKey, Map<String, ModelResolver.VariantRotation>> variantRotations = new HashMap<>();
        if (root.has("blocks")) {
            for (Map.Entry<String, JsonElement> blockEntry : root.getAsJsonObject("blocks").entrySet()) {
                BlockKey key = BlockKey.of(blockEntry.getKey());
                ParsedBlock parsed = parseBlock(logger, key, blockEntry.getValue().getAsJsonObject(), skinByHash);
                blocks.put(BakeKey.untinted(key), parsed.chunks);
                if (!parsed.variants.isEmpty()) variantRotations.put(key, parsed.variants);
            }
        }
        return new HeadsRegistry(logger, gridN, version, Map.copyOf(blocks), Map.copyOf(variantRotations));
    }

    private record ParsedBlock(Map<ChunkCoord, Entry> chunks, Map<String, ModelResolver.VariantRotation> variants) {}

    /**
     * Parse one block's JSON object. Accepts both the new schema
     * ({@code {"chunks": {...}, "variants": {...}}}) and the legacy schema
     * (chunk-coord keys directly at the top level, no wrapper). Variant
     * entries are {@code {"x": deg, "y": deg}}; missing components default
     * to 0.
     */
    private static ParsedBlock parseBlock(Logger logger, BlockKey key, JsonObject blockObj,
                                          Map<String, Entry> skinByHash) {
        Map<ChunkCoord, Entry> chunks = new LinkedHashMap<>();
        Map<String, ModelResolver.VariantRotation> variants = new LinkedHashMap<>();

        JsonObject chunksObj = blockObj.has("chunks") ? blockObj.getAsJsonObject("chunks") : blockObj;
        for (Map.Entry<String, JsonElement> chunkEntry : chunksObj.entrySet()) {
            String k = chunkEntry.getKey();
            // Wrapper schema exposes "chunks" and "variants" siblings —
            // skip those when iterating legacy-style.
            if (chunksObj == blockObj && (k.equals("chunks") || k.equals("variants"))) continue;
            ChunkCoord coord;
            try {
                coord = ChunkCoord.parseKey(k);
            } catch (Exception e) {
                continue;
            }
            String hash = chunkEntry.getValue().getAsJsonObject().get("skinHash").getAsString();
            Entry skin = skinByHash.get(hash);
            if (skin == null) {
                logger.warning("[" + key + " " + coord.asKey() + "] references missing skin hash " + hash);
                continue;
            }
            chunks.put(coord, skin);
        }

        if (blockObj.has("variants")) {
            for (Map.Entry<String, JsonElement> ve : blockObj.getAsJsonObject("variants").entrySet()) {
                JsonObject v = ve.getValue().getAsJsonObject();
                int xDeg = v.has("x") ? v.get("x").getAsInt() : 0;
                int yDeg = v.has("y") ? v.get("y").getAsInt() : 0;
                variants.put(ve.getKey(), new ModelResolver.VariantRotation(xDeg, yDeg));
            }
        }
        return new ParsedBlock(Map.copyOf(chunks), Map.copyOf(variants));
    }

    public int gridN() { return gridN; }
    public String version() { return version; }

    public boolean has(BakeKey key) {
        Map<ChunkCoord, Entry> per = blocks.get(key);
        return per != null && !per.isEmpty();
    }

    /** Untinted convenience overload — equivalent to {@code has(BakeKey.untinted(key))}. */
    public boolean has(BlockKey key) {
        return has(BakeKey.untinted(key));
    }

    public Optional<Entry> get(BakeKey key, ChunkCoord coord) {
        Map<ChunkCoord, Entry> per = blocks.get(key);
        return per == null ? Optional.empty() : Optional.ofNullable(per.get(coord));
    }

    public Map<ChunkCoord, Entry> chunksFor(BakeKey key) {
        Map<ChunkCoord, Entry> per = blocks.get(key);
        return per == null ? Collections.emptyMap() : per;
    }

    /** Untinted convenience overload. */
    public Map<ChunkCoord, Entry> chunksFor(BlockKey key) {
        return chunksFor(BakeKey.untinted(key));
    }

    /** Look up a previously-registered skin by its content hash, or null. */
    public Entry findByHash(String hash) {
        return hashIndex.get(hash);
    }

    /**
     * Register chunks for {@code key} discovered at runtime by
     * {@link io.tessera.skin.bake.BlockBaker}. Replaces any existing
     * entry for the same key. {@code variants} is keyed by {@link BlockKey}
     * (rotation is tint-independent) so the same variant map is shared by
     * all tinted bakes of the same block.
     */
    public void register(BakeKey key, Map<ChunkCoord, Entry> chunks) {
        register(key, chunks, Collections.emptyMap());
    }

    public void register(BakeKey key, Map<ChunkCoord, Entry> chunks,
                         Map<String, ModelResolver.VariantRotation> variants) {
        Map<ChunkCoord, Entry> chunksCopy = Map.copyOf(chunks);
        Map<String, ModelResolver.VariantRotation> variantsCopy =
                (variants == null || variants.isEmpty()) ? Collections.emptyMap() : Map.copyOf(variants);
        blocks.put(key, chunksCopy);
        if (!variantsCopy.isEmpty()) variantRotations.put(key.block(), variantsCopy);
        for (Entry e : chunksCopy.values()) hashIndex.putIfAbsent(e.skinHash(), e);
        Persistence p = persistence;
        if (p != null) {
            try {
                p.save(key, chunksCopy, variantsCopy);
            } catch (RuntimeException re) {
                logger.warning("[heads-registry] persistence save failed for " + key + ": " + re.getMessage());
            }
        }
    }

    /**
     * Wire a {@link Persistence} sink to mirror future {@link #register} /
     * {@link #invalidate} / {@link #invalidateAll} calls to disk. Pass
     * {@code null} to detach. Already-loaded entries (bundled heads.json) are
     * <i>not</i> replayed through the sink — call sites that load from
     * persistence should bypass this hook by using
     * {@link #registerSilently}.
     */
    public void setPersistence(Persistence p) {
        this.persistence = p;
    }

    /**
     * Like {@link #register} but bypasses the {@link Persistence} sink. Used
     * when loading existing persisted entries back into the registry at
     * startup — re-saving them would be redundant and could amplify any
     * format drift.
     */
    public void registerSilently(BakeKey key, Map<ChunkCoord, Entry> chunks,
                                 Map<String, ModelResolver.VariantRotation> variants) {
        Persistence saved = this.persistence;
        this.persistence = null;
        try {
            register(key, chunks, variants);
        } finally {
            this.persistence = saved;
        }
    }

    /**
     * Look up the world-space rotation for a specific blockstate variant of
     * {@code key}. The {@code variantKey} should match a vanilla blockstate
     * variant key like {@code "axis=x"} or {@code "facing=west,lit=false"}.
     * Returns identity if {@code key} has no variant rotations registered or
     * if {@code variantKey} doesn't match any known variant — both cases
     * fall back to "render as canonical orientation", which is the correct
     * behavior for blocks pre-dating this feature.
     */
    public Quaternionf rotationFor(BlockKey key, String variantKey) {
        Map<String, ModelResolver.VariantRotation> variants = variantRotations.get(key);
        if (variants == null || variantKey == null) return new Quaternionf();
        ModelResolver.VariantRotation rot = variants.get(variantKey);
        return rot != null ? rot.toQuat() : new Quaternionf();
    }

    public Map<String, ModelResolver.VariantRotation> variantsFor(BlockKey key) {
        Map<String, ModelResolver.VariantRotation> variants = variantRotations.get(key);
        return variants == null ? Collections.emptyMap() : variants;
    }

    /**
     * Forget every runtime registration for {@code block} (untinted plus
     * every tinted variant) so the next request re-runs the bake. Bundled
     * heads.json entries are also cleared by this — re-launching the plugin
     * will reload them from disk.
     */
    public boolean invalidate(BlockKey block) {
        variantRotations.remove(block);
        boolean removed = blocks.keySet().removeIf(k -> k.block().equals(block));
        Persistence p = persistence;
        if (p != null) {
            try { p.remove(block); }
            catch (RuntimeException re) { logger.warning("[heads-registry] persistence remove failed: " + re.getMessage()); }
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
        int n = blocks.size();
        blocks.clear();
        variantRotations.clear();
        // hashIndex stays — its only consumer is BlockBaker.findByHash,
        // which is bypassed when TileRotations.consumeStale is true.
        Persistence p = persistence;
        if (p != null) {
            try { p.clear(); }
            catch (RuntimeException re) { logger.warning("[heads-registry] persistence clear failed: " + re.getMessage()); }
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
}
