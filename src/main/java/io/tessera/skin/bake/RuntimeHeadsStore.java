package io.tessera.skin.bake;

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
 * runtime by {@link BlockBaker}. Bundled {@code heads-{N}.json} ships in the
 * jar resources and never changes; this file
 * ({@code plugins/Tessera/cache/heads-{gridN}.json}) captures everything baked
 * on demand so a server restart doesn't redo the full asset → split → pack →
 * assemble pipeline for every previously-seen block. Per-grid-size files mean
 * switching {@code chunkGridSize} in config doesn't discard prior bakes —
 * each size keeps its own state.
 *
 * <p>Schema mirrors the bundled file (chunks + variants + skins) but the
 * block-key form is a {@link BakeKey} string ({@code "minecraft:oak_leaves#7fbf2e"}
 * for tinted entries) so different biome tints of the same block coexist.
 * See {@link HeadsJsonCodec}.
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
     * The filename already encodes the grid size, so this check is
     * defense-in-depth against a hand-edited or misnamed file.
     */
    public void loadInto(HeadsRegistry registry) {
        if (!Files.isRegularFile(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            HeadsJsonCodec.Document<BakeKey> doc = HeadsJsonCodec.read(
                    json, BakeKey::parse, gridN, version, logger);
            if (doc.gridN() != registry.gridN()) {
                logger.warning("[heads-cache] " + file + " was baked at gridN=" + doc.gridN()
                        + " but current gridN=" + registry.gridN() + "; discarding");
                return;
            }
            if (!doc.version().equals(registry.version())) {
                logger.warning("[heads-cache] " + file + " was baked for version=" + doc.version()
                        + " but current version=" + registry.version()
                        + "; discarding (asset textures may have changed)");
                return;
            }

            int loaded = 0;
            for (Map.Entry<BakeKey, HeadsJsonCodec.Block> e : doc.blocks().entrySet()) {
                BakeKey key = e.getKey();
                Map<ChunkCoord, HeadsRegistry.Entry> chunks = e.getValue().chunks();
                Map<String, ModelResolver.VariantRotation> v = e.getValue().variants();
                if (chunks.isEmpty()) continue;
                blocks.put(key, Map.copyOf(chunks));
                if (!v.isEmpty()) variants.put(key.block(), Map.copyOf(v));
                registry.registerSilently(key, chunks, v);
                loaded++;
            }
            logger.info("[heads-cache] loaded " + loaded + " block entries from " + file);
        } catch (IOException io) {
            logger.warning("[heads-cache] failed to read " + file + ": " + io.getMessage());
        } catch (RuntimeException re) {
            logger.log(Level.WARNING, "[heads-cache] malformed " + file, re);
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
        Map<BakeKey, HeadsJsonCodec.Block> docBlocks = new LinkedHashMap<>();
        for (Map.Entry<BakeKey, Map<ChunkCoord, HeadsRegistry.Entry>> be : blocks.entrySet()) {
            Map<String, ModelResolver.VariantRotation> v = variants.getOrDefault(be.getKey().block(), Map.of());
            docBlocks.put(be.getKey(), new HeadsJsonCodec.Block(be.getValue(), v));
        }
        String json = HeadsJsonCodec.write(
                new HeadsJsonCodec.Document<>(version, gridN, docBlocks),
                BakeKey::toString);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException io) {
            logger.log(Level.WARNING, "[heads-cache] failed to write " + file, io);
        }
    }

    public int size() { return blocks.size(); }
}
