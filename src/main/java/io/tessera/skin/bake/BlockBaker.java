package io.tessera.skin.bake;

import io.tessera.assets.fetch.McAssetClient;
import io.tessera.assets.model.BlockModel;
import io.tessera.assets.model.ModelResolver;
import io.tessera.core.BlockKey;
import io.tessera.core.ChunkCoord;
import io.tessera.core.ChunkSpec;
import io.tessera.skin.HeadSkin;
import io.tessera.skin.HeadSkinPacker;
import io.tessera.skin.HeadsRegistry;
import io.tessera.skin.SkinAssembler;
import io.tessera.skin.SkinDiskCache;
import io.tessera.skin.SkinState;
import io.tessera.skin.SkinUploader;
import io.tessera.skin.TileRotations;
import io.tessera.split.TextureSplitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bakes a single block on demand: resolve assets → split textures → pack
 * heads → assemble PNGs → upload to MineSkin → register in
 * {@link HeadsRegistry}. Used both by the runtime listener (when a player
 * breaks a block we haven't baked yet) and the {@link BakeMain} CLI.
 *
 * <p>Concurrency: dedups simultaneous requests for the same {@link BlockKey}
 * via {@link #inflight} so a single block break doesn't kick off N parallel
 * bakes if the player spams. The future returned by {@link #bake} resolves
 * to {@code true} if the block was successfully registered; {@code false}
 * means it was unbakeable (non-cube, tinted, asset 404, etc.).
 *
 * <p>Cache bypass: when the global "stale" flag in
 * {@link io.tessera.skin.TileRotations#consumeStale} is set, the next bake
 * for any block tells {@link SkinUploader} to skip its skin-hash cache and
 * upload fresh PNGs. This is how {@code /tessera debug tilerot} forces a
 * re-upload after changing in-plane rotation.
 */
public final class BlockBaker {

    private final Logger logger;
    private final ModelResolver resolver;
    private final TextureSplitter splitter;
    private final HeadSkinPacker packer;
    private final SkinAssembler assembler;
    private final SkinUploader uploader;
    private final HeadsRegistry registry;
    private final SkinDiskCache diskCache;
    private final Path pngDir;
    private final Executor executor;

    private final Map<BlockKey, CompletableFuture<Boolean>> inflight = new ConcurrentHashMap<>();

    public BlockBaker(Logger logger,
                      McAssetClient assets,
                      String mcVersion,
                      HeadsRegistry registry,
                      SkinUploader uploader,
                      SkinDiskCache diskCache,
                      Path pngDir,
                      Executor executor) {
        this.logger = logger;
        this.resolver = new ModelResolver(assets, logger, mcVersion);
        this.splitter = new TextureSplitter();
        this.packer = new HeadSkinPacker();
        this.assembler = new SkinAssembler();
        this.uploader = uploader;
        this.registry = registry;
        this.diskCache = diskCache;
        this.pngDir = pngDir;
        this.executor = executor;
    }

    public CompletableFuture<Boolean> bake(BlockKey key) {
        return inflight.computeIfAbsent(key, this::startBake);
    }

    private CompletableFuture<Boolean> startBake(BlockKey key) {
        boolean bypassCache = TileRotations.consumeStale();
        CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> {
            try {
                return doBake(key, bypassCache);
            } catch (Exception e) {
                logger.log(Level.WARNING, "[runtime-bake] " + key + " failed", e);
                return false;
            }
        }, executor);
        f.whenComplete((v, ex) -> inflight.remove(key));
        return f;
    }

    private boolean doBake(BlockKey key, boolean bypassCache)
            throws IOException, ExecutionException, InterruptedException, TimeoutException {

        if (uploader == null || !uploader.isReady()) {
            logger.warning("[runtime-bake] " + key + " skipped — no MineSkin client (set mineskinApiKey)");
            return false;
        }

        Optional<BlockModel> modelOpt = resolver.resolve(key);
        if (modelOpt.isEmpty()) {
            logger.fine("[runtime-bake] " + key + " unsupported (non-cube or asset missing)");
            return false;
        }
        BlockModel model = modelOpt.get();
        if (model.tinted()) {
            logger.fine("[runtime-bake] " + key + " skipped (tinted)");
            return false;
        }

        int gridN = registry.gridN();
        List<ChunkSpec> chunks = splitter.split(model, gridN);
        HeadSkinPacker.Result packed = packer.pack(chunks);
        logger.info("[runtime-bake] " + key + " — " + chunks.size() + " chunks → "
                + packed.uniqueHeads().size() + " unique heads"
                + (bypassCache ? " (cache bypassed)" : ""));

        // Two-tier cache lookup:
        // 1. In-memory registry (cheap; survives within a session) — keyed
        //    by pre-paint contentHash. Skipped when bypassCache is set
        //    (i.e. just after a TileRotations change) because the post-paint
        //    bytes will differ even though the pre-paint hash hasn't.
        // 2. Persistent SkinDiskCache — keyed by the SHA-256 of the actual
        //    painted PNG bytes, so any combination of source/tile rotations
        //    that produces the same final image hits cache regardless of how
        //    we got there. Survives plugin restarts.
        // Heads that miss both tiers get assembled + queued for upload.
        List<HeadSkin> needUpload = new java.util.ArrayList<>();
        java.util.Map<HeadSkin, String> pngHashByHead = new java.util.HashMap<>();
        for (HeadSkin head : packed.uniqueHeads()) {
            HeadsRegistry.Entry registryHit = bypassCache ? null : registry.findByHash(head.contentHash());
            if (registryHit != null) {
                head.texture(registryHit.textureValue(), registryHit.textureSignature(), registryHit.mineskinUuid());
                head.state(SkinState.COMPLETED);
                continue;
            }
            // Have to assemble before we can hash post-paint bytes — but the
            // assemble step is cheap (pure local PNG paint, no I/O of size).
            java.nio.file.Path pngPath = assembler.assemble(head, pngDir);
            byte[] pngBytes = java.nio.file.Files.readAllBytes(pngPath);
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

        if (!needUpload.isEmpty()) {
            logger.info("[runtime-bake] " + key + " uploading " + needUpload.size()
                    + " new skins ("
                    + (packed.uniqueHeads().size() - needUpload.size())
                    + " hit cache)");
            SkinUploader.Run run = uploader.upload(needUpload, pngDir.getParent(), h -> {
                // As each upload completes, persist the (png-hash → texture)
                // entry so a kill mid-bake still preserves work done so far.
                if (diskCache != null && h.state() == SkinState.COMPLETED) {
                    String pngHash = pngHashByHead.get(h);
                    if (pngHash != null) {
                        diskCache.put(pngHash, h.textureValue(), h.textureSignature(), h.mineskinUuid());
                    }
                }
            });
            run.future().get(5, TimeUnit.MINUTES);
        }

        Map<ChunkCoord, HeadsRegistry.Entry> chunkMap = new LinkedHashMap<>();
        int completedHeads = 0, erroredHeads = 0;
        for (HeadSkin head : packed.uniqueHeads()) {
            if (head.state() == SkinState.COMPLETED) completedHeads++;
            else erroredHeads++;
        }
        packed.chunkToHead().forEach((chunk, head) -> {
            if (head.state() == SkinState.COMPLETED) {
                chunkMap.put(chunk.coord(), new HeadsRegistry.Entry(
                        head.contentHash(), head.textureValue(),
                        head.textureSignature(), head.mineskinUuid()));
            }
        });
        // Always log completion stats so the user can spot rate-limit /
        // upload errors that produce partially-baked blocks (visible as
        // "missing chunks" in /tessera test).
        logger.info("[runtime-bake] " + key + " complete: "
                + completedHeads + "/" + packed.uniqueHeads().size() + " heads succeeded, "
                + chunkMap.size() + "/" + chunks.size() + " chunks registered"
                + (erroredHeads > 0 ? " (" + erroredHeads + " heads errored - retry to fill in)" : ""));

        if (chunkMap.isEmpty()) {
            logger.warning("[runtime-bake] " + key + " produced 0 completed chunks");
            return false;
        }

        registry.register(key, chunkMap);
        Files.createDirectories(pngDir);
        return true;
    }

    /**
     * Diagnostic-only: split + pack + assemble {@code key}'s textures locally
     * and write one PNG per chunk into {@code outDir} with chunk-coordinate
     * filenames (e.g. {@code 3-3-1.png}). No upload, no registry side-effects.
     * Lets a developer inspect exactly what bytes are being painted into each
     * chunk's head canvas — pairs with {@code /tessera debug dumppng}.
     */
    public int dumpPng(BlockKey key, Path outDir) throws IOException {
        Optional<BlockModel> modelOpt = resolver.resolve(key);
        if (modelOpt.isEmpty()) return 0;
        BlockModel model = modelOpt.get();

        int gridN = registry.gridN();
        List<ChunkSpec> chunks = splitter.split(model, gridN);
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
        // Clean up the UUID-named intermediate files SkinAssembler wrote.
        try (var stream = Files.list(outDir)) {
            stream.filter(p -> {
                String n = p.getFileName().toString();
                // Heuristic: UUID has 4 dashes. Coord names have 2.
                return n.endsWith(".png") && n.length() > 12 && n.chars().filter(ch -> ch == '-').count() >= 4;
            }).forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
        return written;
    }
}
