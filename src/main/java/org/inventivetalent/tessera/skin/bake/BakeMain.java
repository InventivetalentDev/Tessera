package org.inventivetalent.tessera.skin.bake;

import org.inventivetalent.tessera.assets.fetch.McAssetClient;
import org.inventivetalent.tessera.assets.model.BlockModel;
import org.inventivetalent.tessera.assets.model.ModelResolver;
import org.inventivetalent.tessera.assets.model.ModelResolver.VariantRotation;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.ChunkSpec;
import org.inventivetalent.tessera.skin.HeadSkin;
import org.inventivetalent.tessera.skin.HeadSkinPacker;
import org.inventivetalent.tessera.skin.SkinAssembler;
import org.inventivetalent.tessera.skin.SkinState;
import org.inventivetalent.tessera.skin.SkinUploader;
import org.inventivetalent.tessera.skin.store.JsonMigrator;
import org.inventivetalent.tessera.skin.store.TsraFolderStore;
import org.inventivetalent.tessera.skin.store.TsraFormat;
import org.inventivetalent.tessera.split.TextureSplitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone entry point for the {@code ./gradlew tesseraBake} task. Reads
 * a list of block IDs from {@code bake-blocks.txt}, fetches their assets
 * from mcasset.cloud, splits the textures, packs heads, uploads to
 * MineSkin, and writes a {@code heads-{gridN}.ztsra} resource zip.
 *
 * <p>Workflow: bakes into a scratch {@link TsraFolderStore} under
 * {@code build/} (re-readable across re-runs so an interrupted bake doesn't
 * waste prior uploads) and zips that folder into the requested {@code .ztsra}
 * output. Idempotent — re-running with the same input is a no-op once every
 * unique skin is present in the folder store.
 *
 * <p>Args:
 * <pre>
 *   --input    bake-blocks.txt (one block ID per line, # comments)
 *   --out      heads-{gridN}.ztsra output path (defaults derive from gridN)
 *   --cache    cache root for assets + skin PNGs + scratch folder store
 *   --version  Minecraft version tag (defaults to {@code 1.21.4})
 *   --gridN    chunk grid size (defaults to 4)
 * </pre>
 *
 * <p>Reads {@code MINESKIN_API_KEY} from the environment. Uploads work
 * without it on the free tier but at low throughput; with it, MineSkin
 * lifts the per-IP rate limit.
 */
public final class BakeMain {

    public static void main(String[] argv) throws Exception {
        Map<String, String> args = parseArgs(argv);
        Path inputPath  = Path.of(args.getOrDefault("input",  "bake-blocks.txt"));
        Path cacheRoot  = Path.of(args.getOrDefault("cache",  "build/tessera-cache"));
        String version  = args.getOrDefault("version", "1.21.4");
        int gridN       = Integer.parseInt(args.getOrDefault("gridN", "4"));
        if (gridN < 1 || gridN > 16 || 16 % gridN != 0) {
            throw new IllegalArgumentException(
                    "gridN must be one of 1, 2, 4, 8, 16; got " + gridN
                            + " (TesseraConfig only loads heads-<N>.ztsra for these sizes)");
        }
        Path outPath    = Path.of(args.getOrDefault("out",
                "src/main/resources/heads-" + gridN + TsraFormat.ZIP_EXTENSION));

        Logger logger = Logger.getLogger("tessera-bake");
        logger.setUseParentHandlers(false);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.INFO);
        logger.addHandler(ch);
        logger.setLevel(Level.INFO);

        if (!Files.isRegularFile(inputPath)) {
            logger.severe("Input file not found: " + inputPath.toAbsolutePath());
            System.exit(1);
        }

        List<BlockKey> blocks = readBlockList(inputPath);
        logger.info("Baking " + blocks.size() + " blocks at version " + version + " (gridN=" + gridN + ")");

        Files.createDirectories(cacheRoot);
        Path pngDir = cacheRoot.resolve("head-pngs");
        Files.createDirectories(pngDir);

        // Scratch folder store: persists across re-runs so a re-bake skips
        // unchanged blocks via store.readSkin / store.readBlock hits.
        // No extension on the directory itself — only the individual .tsra
        // payload files inside it carry the format extension.
        Path scratchRoot = cacheRoot.resolve("heads-" + gridN);
        TsraFolderStore scratch = new TsraFolderStore(logger, scratchRoot);
        scratch.writeManifest(new TsraFormat.Manifest(gridN, version, "tessera-bake-cli"));

        // One-time migration from any leftover heads-{gridN}.json sitting
        // beside the scratch root, so users with an existing pre-tsra
        // workspace don't lose their cached uploads.
        Path legacy = cacheRoot.resolve("heads-" + gridN + ".json");
        if (Files.isRegularFile(legacy)) {
            int migrated = JsonMigrator.migrate(logger, legacy, scratch, gridN, version);
            if (migrated > 0) Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + ".migrated"));
        }

        McAssetClient assets = new McAssetClient(cacheRoot.resolve("assets"));
        ModelResolver resolver = new ModelResolver(assets, logger, version);
        TextureSplitter splitter = new TextureSplitter();
        HeadSkinPacker packer = new HeadSkinPacker();
        SkinAssembler assembler = new SkinAssembler();
        String apiKey = System.getenv("MINESKIN_API_KEY");
        boolean uploadEnabled = apiKey != null && !apiKey.isBlank();
        if (!uploadEnabled) {
            logger.warning("MINESKIN_API_KEY is not set — bake will resolve assets and pack heads,"
                    + " but will skip uploads. Set the env var to publish skins.");
        }
        SkinUploader uploader = uploadEnabled
                ? new SkinUploader(logger, "Tessera-Bake/0.1", apiKey)
                : null;

        for (BlockKey key : blocks) {
            try {
                bakeOne(key, gridN, resolver, splitter, packer, assembler, uploader, pngDir, scratch, logger);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to bake " + key, e);
            }
        }

        logger.info("Zipping scratch folder -> " + outPath);
        JsonMigrator.zipFolder(scratchRoot, outPath);
        logger.info("Done. " + outPath.getFileName() + " written to " + outPath.toAbsolutePath());
    }

    private static void bakeOne(BlockKey key, int gridN,
                                ModelResolver resolver, TextureSplitter splitter,
                                HeadSkinPacker packer, SkinAssembler assembler,
                                SkinUploader uploader, Path pngDir,
                                TsraFolderStore store, Logger logger)
            throws IOException, ExecutionException, InterruptedException, TimeoutException {

        Optional<BlockModel> modelOpt = resolver.resolve(key);
        if (modelOpt.isEmpty()) {
            logger.info("[" + key + "] skipped (non-cube or asset missing)");
            return;
        }
        BlockModel model = modelOpt.get();
        if (model.tinted()) {
            logger.info("[" + key + "] skipped (tinted block; not supported in v1)");
            return;
        }

        List<ChunkSpec> chunks = splitter.split(model, gridN);
        HeadSkinPacker.Result packed = packer.pack(chunks);
        logger.info("[" + key + "] " + chunks.size() + " visible chunks → "
                + packed.uniqueHeads().size() + " unique heads");

        // Skip uploads for hashes already in the scratch store — but still
        // need to map chunk → entry for the block file.
        BakeKey bakeKey = BakeKey.untinted(key);
        List<HeadSkin> needUpload = new ArrayList<>();
        for (HeadSkin head : packed.uniqueHeads()) {
            Optional<TsraFormat.Skin> existing = store.readSkin(head.contentHash());
            if (existing.isPresent()) {
                TsraFormat.Skin s = existing.get();
                head.texture(s.value(), s.signature(), s.mineskinUuid());
                head.state(SkinState.COMPLETED);
                continue;
            }
            assembler.assemble(head, pngDir);
            needUpload.add(head);
        }

        if (!needUpload.isEmpty()) {
            if (uploader == null) {
                logger.info("[" + key + "] " + needUpload.size() + " skins packed locally (PNGs in "
                        + pngDir.toAbsolutePath() + "); upload skipped (no API key)");
            } else {
                logger.info("[" + key + "] uploading " + needUpload.size() + " new skins to MineSkin");
                SkinUploader.Run run = uploader.upload(needUpload, pngDir.getParent(), h -> {});
                run.future().get(10, TimeUnit.MINUTES);

                for (HeadSkin head : needUpload) {
                    if (head.state() != SkinState.COMPLETED) {
                        logger.warning("[" + key + "] head " + head.id() + " ended in state " + head.state());
                        continue;
                    }
                    store.writeSkin(new TsraFormat.Skin(
                            head.contentHash(), head.textureValue(),
                            head.textureSignature(), head.mineskinUuid()));
                }
            }
        }

        // Build chunk → hash map. Only chunks whose head got a MineSkin
        // texture make it into the block file — otherwise the runtime
        // listener would think the block is supported and spawn FakeBlocks
        // with zero entities (visually a no-op).
        TreeMap<ChunkCoord, String> chunkHashes = new TreeMap<>();
        packed.chunkToHead().forEach((chunk, head) -> {
            if (head.state() == SkinState.COMPLETED) {
                chunkHashes.put(chunk.coord(), head.contentHash());
            }
        });
        if (chunkHashes.isEmpty()) {
            logger.info("[" + key + "] no completed skins; not writing a block entry");
            return;
        }

        // Capture per-variant rotation hints alongside the chunk map so the
        // runtime can orient oak_log[axis=x] etc. correctly without re-
        // parsing the blockstate JSON. Identity-rotation variants
        // (canonical orientation) are dropped — the runtime falls back to
        // identity for unknown variant keys.
        TreeMap<String, VariantRotation> variantMap = new TreeMap<>();
        for (Map.Entry<String, VariantRotation> e : model.variantRotations().entrySet()) {
            if (e.getValue().xDeg() != 0 || e.getValue().yDeg() != 0) {
                variantMap.put(e.getKey(), e.getValue());
            }
        }
        store.writeBlock(new TsraFormat.Block(bakeKey, chunkHashes, variantMap));
    }

    private static List<BlockKey> readBlockList(Path file) throws IOException {
        List<BlockKey> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            out.add(BlockKey.of(line.toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    private static Map<String, String> parseArgs(String[] argv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < argv.length - 1; i += 2) {
            String k = argv[i].startsWith("--") ? argv[i].substring(2) : argv[i];
            m.put(k, argv[i + 1]);
        }
        return m;
    }

}
