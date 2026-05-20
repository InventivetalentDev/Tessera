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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * from mcasset.cloud, splits the textures (per shape, for multi-shape
 * blocks like stairs), packs heads, uploads to MineSkin, and writes a
 * {@code heads-{gridN}.ztsra} resource zip.
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
                    "gridN must be one of 1, 2, 4, 8, 16; got " + gridN);
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

        Path scratchRoot = cacheRoot.resolve("heads-" + gridN);
        TsraFolderStore scratch = new TsraFolderStore(logger, scratchRoot);
        scratch.writeManifest(new TsraFormat.Manifest(gridN, version, "tessera-bake-cli"));

        Path legacy = cacheRoot.resolve("heads-" + gridN + ".json");
        if (Files.isRegularFile(legacy)) {
            int migrated = JsonMigrator.migrate(logger, legacy, scratch, gridN, version);
            if (migrated > 0) Files.move(legacy, legacy.resolveSibling(legacy.getFileName() + ".migrated"));
        }

        McAssetClient assets = new McAssetClient(cacheRoot.resolve("assets"));
        ModelResolver resolver = new ModelResolver(assets, logger, version, gridN);
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

        BakeKey bakeKey = BakeKey.untinted(key);

        // Fast path: a fully-cached block (block file present + every
        // referenced skin payload on disk) needs no work. Multi-shape
        // blocks check every shape's hashes.
        Optional<TsraFormat.Block> cached = store.readBlock(bakeKey);
        if (cached.isPresent()) {
            Set<String> referenced = new HashSet<>();
            for (TsraFormat.Shape s : cached.get().shapes().values()) {
                for (TsraFormat.ChunkRecord cr : s.chunks().values()) referenced.add(cr.hash());
            }
            boolean allPresent = !referenced.isEmpty();
            for (String hash : referenced) {
                if (!store.skinExists(hash)) { allPresent = false; break; }
            }
            if (allPresent) {
                int totalChunks = cached.get().shapes().values().stream()
                        .mapToInt(s -> s.chunks().size()).sum();
                logger.info("[" + key + "] cached (" + cached.get().shapes().size() + " shape(s), "
                        + totalChunks + " chunks, " + referenced.size() + " unique heads)");
                return;
            }
        }

        Optional<BlockModel> modelOpt = resolver.resolve(key);
        if (modelOpt.isEmpty()) {
            logger.info("[" + key + "] skipped (no resolvable shape)");
            return;
        }
        BlockModel model = modelOpt.get();
        if (model.tinted()) {
            logger.info("[" + key + "] skipped (tinted block; not supported in v1)");
            return;
        }

        // Per-shape split + pack. Skin dedup is content-hashed so identical
        // tiles across shapes share a single MineSkin upload.
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
            logger.info("[" + key + "] no chunks produced");
            return;
        }
        int totalChunks = chunksPerShape.values().stream().mapToInt(List::size).sum();
        logger.info("[" + key + "] " + chunksPerShape.size() + " shape(s), "
                + totalChunks + " visible chunks → " + uniqueHeads.size() + " unique heads");

        // Skip uploads for hashes already in the scratch store.
        List<HeadSkin> needUpload = new ArrayList<>();
        for (HeadSkin head : uniqueHeads.values()) {
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

        // Build per-shape chunk records and the variant binding table.
        LinkedHashMap<String, TsraFormat.Shape> shapeRecords = new LinkedHashMap<>();
        for (Map.Entry<String, HeadSkinPacker.Result> se : packedPerShape.entrySet()) {
            TreeMap<ChunkCoord, TsraFormat.ChunkRecord> chunkRecords = new TreeMap<>();
            se.getValue().chunkToHead().forEach((chunk, head) -> {
                if (head.state() == SkinState.COMPLETED) {
                    chunkRecords.put(chunk.coord(), new TsraFormat.ChunkRecord(
                            head.contentHash(), TsraFormat.ChunkRecord.mask(chunk.outwardFaces())));
                }
            });
            if (!chunkRecords.isEmpty()) {
                shapeRecords.put(se.getKey(), new TsraFormat.Shape(chunkRecords));
            }
        }
        if (shapeRecords.isEmpty()) {
            logger.info("[" + key + "] no completed shapes; not writing a block entry");
            return;
        }

        TreeMap<String, TsraFormat.ShapeVariantBinding> variantMap = new TreeMap<>();
        for (Map.Entry<String, BlockModel.VariantBinding> ve : model.variants().entrySet()) {
            BlockModel.VariantBinding vb = ve.getValue();
            // Drop identity-rotation variants pointing at the default shape — the runtime
            // already falls back to (defaultShape, identity) for unmatched variant keys,
            // so storing them is dead weight. Non-default-shape variants are always kept
            // so corner-stairs variants can find their model.
            boolean identityRotation = vb.rotation().xDeg() == 0 && vb.rotation().yDeg() == 0;
            boolean pointsAtDefault = vb.shapeKey().equals(model.defaultShapeKey());
            if (identityRotation && pointsAtDefault) continue;
            String shapeKey = shapeRecords.containsKey(vb.shapeKey()) ? vb.shapeKey() : model.defaultShapeKey();
            variantMap.put(ve.getKey(), new TsraFormat.ShapeVariantBinding(shapeKey, vb.rotation()));
        }
        store.writeBlock(new TsraFormat.Block(bakeKey, shapeRecords, variantMap));
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
