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
    private final Path pngDir;
    private final Executor executor;

    private final Map<BlockKey, CompletableFuture<Boolean>> inflight = new ConcurrentHashMap<>();

    public BlockBaker(Logger logger,
                      McAssetClient assets,
                      String mcVersion,
                      HeadsRegistry registry,
                      SkinUploader uploader,
                      Path pngDir,
                      Executor executor) {
        this.logger = logger;
        this.resolver = new ModelResolver(assets, logger, mcVersion);
        this.splitter = new TextureSplitter();
        this.packer = new HeadSkinPacker();
        this.assembler = new SkinAssembler();
        this.uploader = uploader;
        this.registry = registry;
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

        // Re-use any previously-uploaded skins by content hash unless the
        // tilerot bypass flag was set (in which case the painted PNG bytes
        // would actually differ even though the pre-paint hash matches —
        // we have to re-upload to get the new rotation onto MineSkin).
        List<HeadSkin> needUpload = new java.util.ArrayList<>();
        for (HeadSkin head : packed.uniqueHeads()) {
            HeadsRegistry.Entry cached = bypassCache ? null : registry.findByHash(head.contentHash());
            if (cached != null) {
                head.texture(cached.textureValue(), cached.textureSignature(), cached.mineskinUuid());
                head.state(SkinState.COMPLETED);
                continue;
            }
            assembler.assemble(head, pngDir);
            needUpload.add(head);
        }

        if (!needUpload.isEmpty()) {
            SkinUploader.Run run = uploader.upload(needUpload, pngDir.getParent(), h -> {});
            run.future().get(5, TimeUnit.MINUTES);
        }

        Map<ChunkCoord, HeadsRegistry.Entry> chunkMap = new LinkedHashMap<>();
        packed.chunkToHead().forEach((chunk, head) -> {
            if (head.state() == SkinState.COMPLETED) {
                chunkMap.put(chunk.coord(), new HeadsRegistry.Entry(
                        head.contentHash(), head.textureValue(),
                        head.textureSignature(), head.mineskinUuid()));
            }
        });
        if (chunkMap.isEmpty()) {
            logger.warning("[runtime-bake] " + key + " produced 0 completed chunks");
            return false;
        }

        registry.register(key, chunkMap);
        Files.createDirectories(pngDir);
        return true;
    }
}
