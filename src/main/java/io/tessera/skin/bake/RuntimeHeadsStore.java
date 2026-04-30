package io.tessera.skin.bake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.tessera.assets.model.ModelResolver;
import io.tessera.core.BakeKey;
import io.tessera.core.BlockKey;
import io.tessera.core.ChunkCoord;
import io.tessera.skin.HeadsRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent on-disk store for {@link HeadsRegistry} entries created at
 * runtime by {@link BlockBaker}. Bundled {@code heads.json} ships in the jar
 * resources and never changes; this file ({@code plugins/Tessera/cache/runtime-heads.json})
 * captures everything baked on demand so a server restart doesn't redo the
 * full asset → split → pack → assemble pipeline for every previously-seen
 * block.
 *
 * <p>Schema mirrors {@code heads.json} (chunks + variants + skins) but the
 * block-key form is a {@link BakeKey} string ({@code "minecraft:oak_leaves#7fbf2e"}
 * for tinted entries) so different biome tints of the same block coexist.
 *
 * <p>Writes are atomic (temp + rename) and re-serialize the whole file each
 * call. With realistic counts (hundreds of blocks, kilobyte-scale JSON) this
 * is well under the per-bake budget; the alternative — incremental append —
 * would force a custom format and complicate skin-table dedup.
 */
public final class RuntimeHeadsStore implements HeadsRegistry.Persistence {

    private final Logger logger;
    private final Path file;
    private final int gridN;
    private final String version;

    private final Map<BakeKey, Map<ChunkCoord, HeadsRegistry.Entry>> blocks = new ConcurrentHashMap<>();
    private final Map<BlockKey, Map<String, ModelResolver.VariantRotation>> variants = new ConcurrentHashMap<>();

    public RuntimeHeadsStore(Logger logger, Path file, int gridN, String version) {
        this.logger = logger;
        this.file = file;
        this.gridN = gridN;
        this.version = version;
    }

    /**
     * Read the on-disk file (if present) and replay every entry into
     * {@code registry} via {@link HeadsRegistry#registerSilently}. If the
     * stored {@code gridN} doesn't match the registry's, the file is
     * discarded (its chunk coordinates would render at the wrong resolution).
     */
    public void loadInto(HeadsRegistry registry) {
        if (!Files.isRegularFile(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            int storedGrid = root.has("gridN") ? root.get("gridN").getAsInt() : gridN;
            if (storedGrid != registry.gridN()) {
                logger.warning("[runtime-heads] " + file + " was baked at gridN=" + storedGrid
                        + " but current gridN=" + registry.gridN() + "; discarding");
                return;
            }

            Map<String, HeadsRegistry.Entry> skinByHash = new LinkedHashMap<>();
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

            int loaded = 0;
            if (root.has("blocks")) {
                for (Map.Entry<String, JsonElement> blockEntry : root.getAsJsonObject("blocks").entrySet()) {
                    BakeKey key;
                    try {
                        key = BakeKey.parse(blockEntry.getKey());
                    } catch (RuntimeException re) {
                        logger.warning("[runtime-heads] skipping malformed block key " + blockEntry.getKey());
                        continue;
                    }
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
                                logger.warning("[runtime-heads] " + key + " " + coord.asKey()
                                        + " references missing skin " + hash);
                                continue;
                            }
                            chunks.put(coord, skin);
                        }
                    }
                    Map<String, ModelResolver.VariantRotation> v = new LinkedHashMap<>();
                    if (obj.has("variants")) {
                        for (Map.Entry<String, JsonElement> ve : obj.getAsJsonObject("variants").entrySet()) {
                            JsonObject vo = ve.getValue().getAsJsonObject();
                            int xDeg = vo.has("x") ? vo.get("x").getAsInt() : 0;
                            int yDeg = vo.has("y") ? vo.get("y").getAsInt() : 0;
                            v.put(ve.getKey(), new ModelResolver.VariantRotation(xDeg, yDeg));
                        }
                    }
                    if (chunks.isEmpty()) continue;
                    blocks.put(key, Map.copyOf(chunks));
                    if (!v.isEmpty()) variants.put(key.block(), Map.copyOf(v));
                    registry.registerSilently(key, chunks, v);
                    loaded++;
                }
            }
            logger.info("[runtime-heads] loaded " + loaded + " block entries from " + file);
        } catch (IOException io) {
            logger.warning("[runtime-heads] failed to read " + file + ": " + io.getMessage());
        } catch (RuntimeException re) {
            logger.log(Level.WARNING, "[runtime-heads] malformed " + file, re);
        }
    }

    @Override
    public void save(BakeKey key, Map<ChunkCoord, HeadsRegistry.Entry> chunks,
                     Map<String, ModelResolver.VariantRotation> v) {
        blocks.put(key, Map.copyOf(chunks));
        if (v != null && !v.isEmpty()) variants.put(key.block(), Map.copyOf(v));
        else variants.remove(key.block());
        write();
    }

    @Override
    public void remove(BlockKey block) {
        boolean changed = blocks.keySet().removeIf(k -> k.block().equals(block));
        if (variants.remove(block) != null) changed = true;
        if (changed) write();
    }

    @Override
    public void clear() {
        if (blocks.isEmpty() && variants.isEmpty()) return;
        blocks.clear();
        variants.clear();
        write();
    }

    private synchronized void write() {
        JsonObject root = new JsonObject();
        root.addProperty("version", version);
        root.addProperty("gridN", gridN);

        // Build skins table first so block entries reference by hash only.
        JsonObject skins = new JsonObject();
        for (Map<ChunkCoord, HeadsRegistry.Entry> per : blocks.values()) {
            for (HeadsRegistry.Entry e : per.values()) {
                if (skins.has(e.skinHash())) continue;
                JsonObject sk = new JsonObject();
                sk.addProperty("value", e.textureValue());
                sk.addProperty("signature", e.textureSignature());
                if (e.mineskinUuid() != null) sk.addProperty("mineskinUuid", e.mineskinUuid());
                skins.add(e.skinHash(), sk);
            }
        }

        JsonObject blocksJson = new JsonObject();
        for (Map.Entry<BakeKey, Map<ChunkCoord, HeadsRegistry.Entry>> be : blocks.entrySet()) {
            JsonObject blockObj = new JsonObject();
            JsonObject chunksObj = new JsonObject();
            for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> ce : be.getValue().entrySet()) {
                JsonObject ch = new JsonObject();
                ch.addProperty("skinHash", ce.getValue().skinHash());
                chunksObj.add(ce.getKey().asKey(), ch);
            }
            blockObj.add("chunks", chunksObj);
            Map<String, ModelResolver.VariantRotation> v = variants.get(be.getKey().block());
            if (v != null && !v.isEmpty()) {
                JsonObject vObj = new JsonObject();
                for (Map.Entry<String, ModelResolver.VariantRotation> ve : v.entrySet()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("x", ve.getValue().xDeg());
                    r.addProperty("y", ve.getValue().yDeg());
                    vObj.add(ve.getKey(), r);
                }
                blockObj.add("variants", vObj);
            }
            blocksJson.add(be.getKey().toString(), blockObj);
        }
        root.add("blocks", blocksJson);
        root.add("skins", skins);

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException io) {
            logger.log(Level.WARNING, "[runtime-heads] failed to write " + file, io);
        }
    }

    public int size() { return blocks.size(); }
}
