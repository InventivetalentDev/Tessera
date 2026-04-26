package io.tessera.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.tessera.core.BlockKey;
import io.tessera.core.ChunkCoord;

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
 *       "0,0,0": { "skinHash": "abc..." },
 *       "1,0,0": { "skinHash": "abc..." }
 *     }
 *   },
 *   "skins": {
 *     "abc...": { "value": "ey...", "signature": "...", "mineskinUuid": "..." }
 *   }
 * }
 * }</pre>
 * The two-level structure (block→hash, hash→texture) means uniform blocks
 * inflate to ~1KB instead of ~50KB on disk.
 */
public final class HeadsRegistry {

    public record Entry(String skinHash, String textureValue, String textureSignature, String mineskinUuid) {}

    private final Logger logger;
    private final int gridN;
    private final String version;
    private final Map<BlockKey, Map<ChunkCoord, Entry>> blocks;
    private final Map<String, Entry> hashIndex;

    private HeadsRegistry(Logger logger, int gridN, String version,
                          Map<BlockKey, Map<ChunkCoord, Entry>> blocks) {
        this.logger = logger;
        this.gridN = gridN;
        this.version = version;
        // Make blocks mutable so RuntimeBakeService can register on demand;
        // wrapped in synchronizedMap on read paths since BlockBreakListener
        // (main thread) and BlockBaker (async pool) both access it.
        this.blocks = new java.util.concurrent.ConcurrentHashMap<>(blocks);
        this.hashIndex = new java.util.concurrent.ConcurrentHashMap<>();
        for (Map<ChunkCoord, Entry> per : blocks.values()) {
            for (Entry e : per.values()) hashIndex.putIfAbsent(e.skinHash(), e);
        }
    }

    public static HeadsRegistry empty(Logger logger, int gridN, String version) {
        return new HeadsRegistry(logger, gridN, version, Collections.emptyMap());
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

        Map<BlockKey, Map<ChunkCoord, Entry>> blocks = new HashMap<>();
        if (root.has("blocks")) {
            for (Map.Entry<String, JsonElement> blockEntry : root.getAsJsonObject("blocks").entrySet()) {
                BlockKey key = BlockKey.of(blockEntry.getKey());
                Map<ChunkCoord, Entry> chunks = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> chunkEntry : blockEntry.getValue().getAsJsonObject().entrySet()) {
                    ChunkCoord coord = ChunkCoord.parseKey(chunkEntry.getKey());
                    String hash = chunkEntry.getValue().getAsJsonObject().get("skinHash").getAsString();
                    Entry skin = skinByHash.get(hash);
                    if (skin == null) {
                        logger.warning("[" + key + " " + coord.asKey() + "] references missing skin hash " + hash);
                        continue;
                    }
                    chunks.put(coord, skin);
                }
                blocks.put(key, Map.copyOf(chunks));
            }
        }
        return new HeadsRegistry(logger, gridN, version, Map.copyOf(blocks));
    }

    public int gridN() { return gridN; }
    public String version() { return version; }

    public boolean has(BlockKey key) {
        Map<ChunkCoord, Entry> per = blocks.get(key);
        return per != null && !per.isEmpty();
    }

    public Optional<Entry> get(BlockKey key, ChunkCoord coord) {
        Map<ChunkCoord, Entry> per = blocks.get(key);
        return per == null ? Optional.empty() : Optional.ofNullable(per.get(coord));
    }

    public Map<ChunkCoord, Entry> chunksFor(BlockKey key) {
        Map<ChunkCoord, Entry> per = blocks.get(key);
        return per == null ? Collections.emptyMap() : per;
    }

    /** Look up a previously-registered skin by its content hash, or null. */
    public Entry findByHash(String hash) {
        return hashIndex.get(hash);
    }

    /**
     * Register chunks for {@code key} discovered at runtime by
     * {@link io.tessera.skin.bake.BlockBaker}. Replaces any existing
     * entry for the same key.
     */
    public void register(BlockKey key, Map<ChunkCoord, Entry> chunks) {
        blocks.put(key, Map.copyOf(chunks));
        for (Entry e : chunks.values()) hashIndex.putIfAbsent(e.skinHash(), e);
    }

    /**
     * Forget runtime registration for {@code key} so the next request
     * re-runs the bake. Bundled heads.json entries are also cleared by
     * this — re-launching the plugin will reload them from disk.
     */
    public boolean invalidate(BlockKey key) {
        return blocks.remove(key) != null;
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
        // hashIndex stays — its only consumer is BlockBaker.findByHash,
        // which is bypassed when TileRotations.consumeStale is true.
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
