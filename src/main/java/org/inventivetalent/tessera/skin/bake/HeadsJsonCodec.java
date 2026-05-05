package org.inventivetalent.tessera.skin.bake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.inventivetalent.tessera.assets.model.ModelResolver.VariantRotation;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.skin.HeadsRegistry;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Read/write logic for the {@code heads-{N}.json} schema. Used by both the
 * bundled file produced by {@link BakeMain} (block-key form: {@link BlockKey})
 * and the runtime cache file managed by {@link RuntimeHeadsStore} (block-key
 * form: {@link BakeKey}). The schema is identical between the
 * two — only the block-key string is parsed/rendered differently — so both
 * paths funnel through this class.
 *
 * <p>Schema:
 * <pre>{@code
 * {
 *   "version": "1.21.4",
 *   "gridN": 4,
 *   "skins": {
 *     "<hash>": { "value": "...", "signature": "...", "mineskinUuid": "..." }
 *   },
 *   "blocks": {
 *     "<key>": {
 *       "chunks":   { "0,0,0": { "skinHash": "<hash>" }, ... },
 *       "variants": { "axis=x": { "x": 90, "y": 90 }, ... }   // optional
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Variants whose x and y rotations are both zero are omitted (the runtime
 * falls back to identity for unknown variant keys, which is correct for
 * canonical orientation). Skin entries are deduplicated by hash so uniform
 * blocks stay compact on disk.
 */
public final class HeadsJsonCodec {

    private HeadsJsonCodec() {}

    public record Document<K>(String version, int gridN, Map<K, Block> blocks) {}

    public record Block(Map<ChunkCoord, HeadsRegistry.Entry> chunks,
                        Map<String, VariantRotation> variants) {}

    /**
     * Parse a JSON document. Block keys are produced via {@code parseKey};
     * if it returns {@code null} or throws, the block is skipped with a
     * warning. Chunks referencing a missing skin hash are skipped.
     */
    public static <K> Document<K> read(String json, Function<String, K> parseKey,
                                       int defaultGridN, String defaultVersion, Logger logger) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        int gridN = root.has("gridN") ? root.get("gridN").getAsInt() : defaultGridN;
        String version = root.has("version") ? root.get("version").getAsString() : defaultVersion;

        Map<String, HeadsRegistry.Entry> skinByHash = new HashMap<>();
        if (root.has("skins")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("skins").entrySet()) {
                JsonObject sk = e.getValue().getAsJsonObject();
                skinByHash.put(e.getKey(), new HeadsRegistry.Entry(
                        e.getKey(),
                        sk.get("value").getAsString(),
                        sk.get("signature").getAsString(),
                        sk.has("mineskinUuid") ? sk.get("mineskinUuid").getAsString() : null));
            }
        }

        Map<K, Block> blocks = new LinkedHashMap<>();
        if (root.has("blocks")) {
            for (Map.Entry<String, JsonElement> blockEntry : root.getAsJsonObject("blocks").entrySet()) {
                K key;
                try {
                    key = parseKey.apply(blockEntry.getKey());
                } catch (RuntimeException re) {
                    logger.warning("[heads-json] skipping malformed block key " + blockEntry.getKey());
                    continue;
                }
                if (key == null) continue;
                JsonObject obj = blockEntry.getValue().getAsJsonObject();

                Map<ChunkCoord, HeadsRegistry.Entry> chunks = new LinkedHashMap<>();
                if (obj.has("chunks")) {
                    for (Map.Entry<String, JsonElement> ce : obj.getAsJsonObject("chunks").entrySet()) {
                        ChunkCoord coord;
                        try { coord = ChunkCoord.parseKey(ce.getKey()); }
                        catch (RuntimeException ignored) { continue; }
                        String hash = ce.getValue().getAsJsonObject().get("skinHash").getAsString();
                        HeadsRegistry.Entry skin = skinByHash.get(hash);
                        if (skin == null) {
                            logger.warning("[heads-json] " + key + " " + coord.asKey()
                                    + " references missing skin " + hash);
                            continue;
                        }
                        chunks.put(coord, skin);
                    }
                }

                Map<String, VariantRotation> variants = new LinkedHashMap<>();
                if (obj.has("variants")) {
                    for (Map.Entry<String, JsonElement> ve : obj.getAsJsonObject("variants").entrySet()) {
                        JsonObject vo = ve.getValue().getAsJsonObject();
                        int xDeg = vo.has("x") ? vo.get("x").getAsInt() : 0;
                        int yDeg = vo.has("y") ? vo.get("y").getAsInt() : 0;
                        variants.put(ve.getKey(), new VariantRotation(xDeg, yDeg));
                    }
                }

                blocks.put(key, new Block(chunks, variants));
            }
        }
        return new Document<>(version, gridN, blocks);
    }

    /**
     * Serialize {@code doc} to a pretty-printed JSON string. Blocks and
     * chunks are emitted in {@code renderKey}-sorted order so the output is
     * deterministic; the skin table is collected from all chunk references
     * and deduped by hash.
     */
    public static <K> String write(Document<K> doc, Function<K, String> renderKey) {
        JsonObject root = new JsonObject();
        root.addProperty("version", doc.version());
        root.addProperty("gridN", doc.gridN());

        // Build the skin table from all referenced entries first so blocks
        // can reference by hash only. TreeMap orders by hash for a stable
        // diff across rewrites.
        Map<String, HeadsRegistry.Entry> skinTable = new TreeMap<>();
        for (Block b : doc.blocks().values()) {
            for (HeadsRegistry.Entry e : b.chunks().values()) {
                skinTable.putIfAbsent(e.skinHash(), e);
            }
        }
        JsonObject skinsJson = new JsonObject();
        for (HeadsRegistry.Entry e : skinTable.values()) {
            JsonObject so = new JsonObject();
            so.addProperty("value", e.textureValue());
            so.addProperty("signature", e.textureSignature());
            if (e.mineskinUuid() != null) so.addProperty("mineskinUuid", e.mineskinUuid());
            skinsJson.add(e.skinHash(), so);
        }
        root.add("skins", skinsJson);

        JsonObject blocksJson = new JsonObject();
        doc.blocks().entrySet().stream()
                .sorted(Comparator.comparing(e -> renderKey.apply(e.getKey())))
                .forEach(entry -> {
                    Block b = entry.getValue();
                    JsonObject blockObj = new JsonObject();

                    JsonObject chunksObj = new JsonObject();
                    Map<String, HeadsRegistry.Entry> sortedChunks = new TreeMap<>();
                    for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> ce : b.chunks().entrySet()) {
                        sortedChunks.put(ce.getKey().asKey(), ce.getValue());
                    }
                    sortedChunks.forEach((coordKey, skin) -> {
                        JsonObject ch = new JsonObject();
                        ch.addProperty("skinHash", skin.skinHash());
                        chunksObj.add(coordKey, ch);
                    });
                    blockObj.add("chunks", chunksObj);

                    if (!b.variants().isEmpty()) {
                        JsonObject vJson = new JsonObject();
                        boolean wroteAny = false;
                        for (Map.Entry<String, VariantRotation> ve : new TreeMap<>(b.variants()).entrySet()) {
                            VariantRotation r = ve.getValue();
                            if (r.xDeg() == 0 && r.yDeg() == 0) continue;
                            JsonObject ro = new JsonObject();
                            if (r.xDeg() != 0) ro.addProperty("x", r.xDeg());
                            if (r.yDeg() != 0) ro.addProperty("y", r.yDeg());
                            vJson.add(ve.getKey(), ro);
                            wroteAny = true;
                        }
                        if (wroteAny) blockObj.add("variants", vJson);
                    }

                    blocksJson.add(renderKey.apply(entry.getKey()), blockObj);
                });
        root.add("blocks", blocksJson);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(root);
    }
}
