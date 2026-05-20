package org.inventivetalent.tessera.skin.bake;

import org.inventivetalent.tessera.assets.fetch.McAssetClient;
import org.inventivetalent.tessera.assets.model.BlockModel;
import org.inventivetalent.tessera.assets.model.ModelResolver;
import org.inventivetalent.tessera.assets.model.ShapeContent;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.ChunkSpec;
import org.inventivetalent.tessera.skin.HeadSkin;
import org.inventivetalent.tessera.skin.HeadSkinPacker;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.inventivetalent.tessera.skin.SkinAssembler;
import org.inventivetalent.tessera.skin.SkinDiskCache;
import org.inventivetalent.tessera.skin.SkinState;
import org.inventivetalent.tessera.skin.SkinUploader;
import org.inventivetalent.tessera.skin.TileRotations;
import org.inventivetalent.tessera.split.TextureSplitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bakes a single block on demand: resolve assets → per-shape split → pack
 * heads → assemble PNGs → upload to MineSkin → register in
 * {@link HeadsRegistry}. Used both by the runtime listener (when a player
 * breaks a block we haven't baked yet) and the {@link BakeMain} CLI.
 *
 * <p>Multi-shape handling: a block whose blockstate references multiple
 * model paths (stairs: straight + inner + outer) bakes each shape's chunks
 * separately and registers all of them in one {@link HeadsRegistry#register}
 * call. Skin dedup happens automatically — {@code HeadSkinPacker}
 * content-hashes per chunk, so a uniform-textured stair uploads few unique
 * skins across all three shapes combined.
 *
 * <p>Concurrency: dedups simultaneous requests for the same {@link BakeKey}
 * via {@link #inflight} so a single block break doesn't kick off N parallel
 * bakes if the player spams.
 *
 * <p>Cache bypass: when the global "stale" flag in
 * {@link TileRotations#consumeStale} is set, the next bake for any block
 * tells {@link SkinUploader} to skip its skin-hash cache and upload fresh
 * PNGs. This is how {@code /tessera debug tilerot} forces a re-upload
 * after changing in-plane rotation.
 */
public final class BlockBaker {

    private final Logger logger;
    private final BooleanSupplier debug;
    private final ModelResolver resolver;
    private final TextureSplitter splitter;
    private final HeadSkinPacker packer;
    private final SkinAssembler assembler;
    private final SkinUploader uploader;
    private final HeadsRegistry registry;
    private final SkinDiskCache diskCache;
    private final Path pngDir;
    private final Executor executor;
    private final int gridN;

    private final Map<BakeKey, CompletableFuture<Boolean>> inflight = new ConcurrentHashMap<>();

    public record Plan(int totalChunks, int uniqueHeads, int needUpload) {}

    public BlockBaker(Logger logger,
                      BooleanSupplier debug,
                      McAssetClient assets,
                      String mcVersion,
                      HeadsRegistry registry,
                      SkinUploader uploader,
                      SkinDiskCache diskCache,
                      Path pngDir,
                      Executor executor) {
        this.logger = logger;
        this.debug = debug;
        this.gridN = registry.gridN();
        this.resolver = new ModelResolver(assets, logger, mcVersion, this.gridN);
        this.splitter = new TextureSplitter();
        this.packer = new HeadSkinPacker();
        this.assembler = new SkinAssembler();
        this.uploader = uploader;
        this.registry = registry;
        this.diskCache = diskCache;
        this.pngDir = pngDir;
        this.executor = executor;
    }

    public CompletableFuture<Boolean> bake(BakeKey key) {
        return bake(key, null);
    }

    public CompletableFuture<Boolean> bake(BakeKey key, Consumer<Plan> onPlan) {
        return inflight.computeIfAbsent(key, k -> startBake(k, onPlan));
    }

    private CompletableFuture<Boolean> startBake(BakeKey key, Consumer<Plan> onPlan) {
        boolean bypassCache = TileRotations.consumeStale();
        CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> {
            try {
                return doBake(key, bypassCache, onPlan);
            } catch (Exception e) {
                logger.log(Level.WARNING, "[runtime-bake] " + key + " failed", e);
                return false;
            }
        }, executor);
        f.whenComplete((v, ex) -> inflight.remove(key));
        return f;
    }

    private boolean doBake(BakeKey key, boolean bypassCache, Consumer<Plan> onPlan)
            throws IOException, ExecutionException, InterruptedException, TimeoutException {

        if (uploader == null || !uploader.isReady()) {
            logger.warning("[runtime-bake] " + key + " skipped — no MineSkin client (set mineskinApiKey)");
            return false;
        }

        Optional<BlockModel> modelOpt = resolver.resolve(key.block());
        if (modelOpt.isEmpty()) {
            if (debug.getAsBoolean()) logger.info("[runtime-bake] " + key + " unsupported (no resolvable shape)");
            return false;
        }
        BlockModel model = modelOpt.get();
        if (model.tinted()) {
            if (key.tintArgb() == 0) {
                if (debug.getAsBoolean()) logger.info("[runtime-bake] " + key + " skipped (tinted block, no tint provided)");
                return false;
            }
            model = model.withTint(key.tintArgb());
        }

        // Per-shape pipeline. Collect (shape → list of ChunkSpec) plus the
        // union of unique heads across shapes (so we de-dup uploads).
        LinkedHashMap<String, List<ChunkSpec>> chunksPerShape = new LinkedHashMap<>();
        LinkedHashMap<String, HeadSkinPacker.Result> packedPerShape = new LinkedHashMap<>();
        LinkedHashMap<String, HeadSkin> uniqueHeads = new LinkedHashMap<>();
        for (Map.Entry<String, ShapeContent> se : model.shapes().entrySet()) {
            List<ChunkSpec> chunks = splitter.split(se.getValue(), gridN);
            if (chunks.isEmpty()) continue;
            HeadSkinPacker.Result packed = packer.pack(chunks);
            chunksPerShape.put(se.getKey(), chunks);
            packedPerShape.put(se.getKey(), packed);
            for (HeadSkin h : packed.uniqueHeads()) uniqueHeads.putIfAbsent(h.contentHash(), h);
        }
        if (chunksPerShape.isEmpty()) {
            logger.warning("[runtime-bake] " + key + " all shapes empty after split");
            return false;
        }
        int totalChunks = chunksPerShape.values().stream().mapToInt(List::size).sum();
        logger.info("[runtime-bake] " + key + " — " + chunksPerShape.size() + " shape(s), "
                + totalChunks + " chunks → " + uniqueHeads.size() + " unique heads"
                + (bypassCache ? " (cache bypassed)" : ""));

        // Two-tier cache lookup, same as before but operating on the union
        // of unique heads across all shapes.
        List<HeadSkin> needUpload = new ArrayList<>();
        Map<HeadSkin, String> pngHashByHead = new HashMap<>();
        for (HeadSkin head : uniqueHeads.values()) {
            HeadsRegistry.Entry registryHit = bypassCache ? null : registry.findByHash(head.contentHash());
            if (registryHit != null) {
                head.texture(registryHit.textureValue(), registryHit.textureSignature(), registryHit.mineskinUuid());
                head.state(SkinState.COMPLETED);
                continue;
            }
            Path pngPath = assembler.assemble(head, pngDir);
            byte[] pngBytes = Files.readAllBytes(pngPath);
            String pngHash = SkinDiskCache.hashPng(pngBytes);
            pngHashByHead.put(head, pngHash);
            SkinDiskCache.Entry diskHit = diskCache == null ? null : diskCache.find(pngHash);
            if (diskHit != null) {
                head.texture(diskHit.value(), diskHit.signature(), diskHit.uuid());
                head.state(SkinState.COMPLETED);
                continue;
            }
            needUpload.add(head);
        }

        if (onPlan != null) {
            try {
                onPlan.accept(new Plan(totalChunks, uniqueHeads.size(), needUpload.size()));
            } catch (RuntimeException re) {
                logger.warning("[runtime-bake] " + key + " onPlan callback threw: " + re.getMessage());
            }
        }

        if (!needUpload.isEmpty()) {
            logger.info("[runtime-bake] " + key + " uploading " + needUpload.size()
                    + " new skins ("
                    + (uniqueHeads.size() - needUpload.size())
                    + " hit cache)");
            SkinUploader.Run run = uploader.upload(needUpload, pngDir.getParent(), h -> {
                if (diskCache != null && h.state() == SkinState.COMPLETED) {
                    String pngHash = pngHashByHead.get(h);
                    if (pngHash != null) {
                        diskCache.put(pngHash, h.textureValue(), h.textureSignature(), h.mineskinUuid());
                    }
                }
            });
            run.future().get(5, TimeUnit.MINUTES);
        }

        // Stitch shapes back together: per-shape map<ChunkCoord, ChunkEntry>.
        LinkedHashMap<String, Map<ChunkCoord, HeadsRegistry.ChunkEntry>> shapeChunks = new LinkedHashMap<>();
        int completedHeads = 0, erroredHeads = 0;
        for (HeadSkin h : uniqueHeads.values()) {
            if (h.state() == SkinState.COMPLETED) completedHeads++;
            else erroredHeads++;
        }
        int totalRegistered = 0;
        for (Map.Entry<String, HeadSkinPacker.Result> entry : packedPerShape.entrySet()) {
            LinkedHashMap<ChunkCoord, HeadsRegistry.ChunkEntry> chunkMap = new LinkedHashMap<>();
            entry.getValue().chunkToHead().forEach((chunk, head) -> {
                if (head.state() == SkinState.COMPLETED) {
                    chunkMap.put(chunk.coord(), new HeadsRegistry.ChunkEntry(
                            new HeadsRegistry.Entry(head.contentHash(), head.textureValue(),
                                    head.textureSignature(), head.mineskinUuid()),
                            chunk.outwardFaces()));
                }
            });
            if (!chunkMap.isEmpty()) {
                shapeChunks.put(entry.getKey(), chunkMap);
                totalRegistered += chunkMap.size();
            }
        }
        logger.info("[runtime-bake] " + key + " complete: "
                + completedHeads + "/" + uniqueHeads.size() + " heads succeeded, "
                + totalRegistered + "/" + totalChunks + " chunks registered"
                + (erroredHeads > 0 ? " (" + erroredHeads + " heads errored - retry to fill in)" : ""));

        if (shapeChunks.isEmpty()) {
            logger.warning("[runtime-bake] " + key + " produced 0 completed chunks");
            return false;
        }

        // Don't register partial bakes: a half-populated registry entry
        // would short-circuit the listener's `registry.has(...)` check and
        // we'd never retry the failed chunks.
        if (totalRegistered < totalChunks) {
            logger.warning("[runtime-bake] " + key + " incomplete ("
                    + totalRegistered + "/" + totalChunks
                    + " chunks); leaving unregistered so the next bake retries the failures.");
            return false;
        }

        // Variant bindings: variantKey → (shapeKey, rotation). Shapes
        // referenced by missing variants fall back to the model's default
        // shape (handled in ModelResolver).
        LinkedHashMap<String, HeadsRegistry.ShapeVariantBinding> variantBindings = new LinkedHashMap<>();
        for (Map.Entry<String, BlockModel.VariantBinding> ve : model.variants().entrySet()) {
            BlockModel.VariantBinding vb = ve.getValue();
            String shapeKey = shapeChunks.containsKey(vb.shapeKey()) ? vb.shapeKey() : model.defaultShapeKey();
            variantBindings.put(ve.getKey(), new HeadsRegistry.ShapeVariantBinding(shapeKey, vb.rotation()));
        }

        registry.register(key, model.defaultShapeKey(), shapeChunks, variantBindings);

        Files.createDirectories(pngDir);
        return true;
    }

    /**
     * Diagnostic-only: split + pack + assemble {@code key}'s default-shape
     * textures locally and write one PNG per chunk into {@code outDir} with
     * chunk-coordinate filenames (e.g. {@code 3-3-1.png}). No upload, no
     * registry side-effects. For multi-shape blocks only the default shape
     * is dumped — corner shapes can be inspected via runtime tests.
     */
    public int dumpPng(BlockKey key, Path outDir) throws IOException {
        Optional<BlockModel> modelOpt = resolver.resolve(key);
        if (modelOpt.isEmpty()) return 0;
        BlockModel model = modelOpt.get();

        ShapeContent defaultShape = model.defaultShape();
        List<ChunkSpec> chunks = splitter.split(defaultShape, gridN);
        HeadSkinPacker.Result packed = packer.pack(chunks);

        Files.createDirectories(outDir);
        int written = 0;
        for (Map.Entry<ChunkSpec, HeadSkin> entry : packed.chunkToHead().entrySet()) {
            ChunkSpec chunk = entry.getKey();
            HeadSkin head = entry.getValue();
            Path tmp = assembler.assemble(head, outDir);
            ChunkCoord c = chunk.coord();
            Path named = outDir.resolve(c.x() + "-" + c.y() + "-" + c.z() + ".png");
            Files.deleteIfExists(named);
            Files.copy(tmp, named);
            written++;
        }
        try (var stream = Files.list(outDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".png") && n.length() > 12 && n.chars().filter(ch -> ch == '-').count() >= 4;
            }).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
        return written;
    }
}
