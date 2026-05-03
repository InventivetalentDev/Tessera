package io.tessera.skin.bake;

import io.tessera.assets.fetch.McAssetClient;
import io.tessera.assets.model.BlockModel;
import io.tessera.assets.model.ModelResolver;
import io.tessera.assets.model.ModelResolver.VariantRotation;
import io.tessera.core.BlockKey;
import io.tessera.core.ChunkCoord;
import io.tessera.core.ChunkSpec;
import io.tessera.skin.HeadSkin;
import io.tessera.skin.HeadSkinPacker;
import io.tessera.skin.HeadsRegistry;
import io.tessera.skin.SkinAssembler;
import io.tessera.skin.SkinState;
import io.tessera.skin.SkinUploader;
import io.tessera.split.TextureSplitter;

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
 * MineSkin, and writes a deterministic {@code heads-{gridN}.json}.
 *
 * <p>Idempotent: skip already-baked entries by hash, so re-running on the
 * same input is a no-op once everything has been uploaded once.
 *
 * <p>Args:
 * <pre>
 *   --input    bake-blocks.txt (one block ID per line, # comments)
 *   --out      heads-{gridN}.json output path (defaults derive from gridN)
 *   --cache    cache root for assets + skin PNGs
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
        Path outPath    = Path.of(args.getOrDefault("out",
                "src/main/resources/heads-" + gridN + ".json"));

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
        Path pngDir = cacheRoot.resolve("heads");
        Files.createDirectories(pngDir);

        McAssetClient assets = new McAssetClient(cacheRoot.resolve("assets"), logger);
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

        // Existing heads-{gridN}.json state, indexed by skinHash → entry, so
        // we can skip uploads we already ran. Idempotent reruns are the goal.
        BakeState state = BakeState.loadOrEmpty(outPath, version, gridN, logger);
        if (state.gridN != gridN || !state.version.equals(version)) {
            logger.warning(outPath + " was baked with version=" + state.version + " gridN=" + state.gridN
                    + ", but this run uses version=" + version + " gridN=" + gridN
                    + ". Discarding existing entries.");
            state = new BakeState(version, gridN);
        }

        for (BlockKey key : blocks) {
            try {
                bakeOne(key, version, gridN, resolver, splitter, packer, assembler, uploader, pngDir, state, logger);
                state.write(outPath);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to bake " + key, e);
            }
        }

        state.write(outPath);
        logger.info("Done. " + outPath.getFileName() + " written to " + outPath.toAbsolutePath());
    }

    private static void bakeOne(BlockKey key, String version, int gridN,
                                ModelResolver resolver, TextureSplitter splitter,
                                HeadSkinPacker packer, SkinAssembler assembler,
                                SkinUploader uploader, Path pngDir,
                                BakeState state, Logger logger) throws IOException, ExecutionException, InterruptedException, TimeoutException {

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

        // Skip uploads for hashes already in state — but still need to map chunk→hash.
        List<HeadSkin> needUpload = new ArrayList<>();
        for (HeadSkin head : packed.uniqueHeads()) {
            HeadsRegistry.Entry existing = state.skinByHash.get(head.contentHash());
            if (existing != null) {
                head.texture(existing.textureValue(), existing.textureSignature(), existing.mineskinUuid());
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
                    state.skinByHash.put(head.contentHash(), new HeadsRegistry.Entry(
                            head.contentHash(), head.textureValue(), head.textureSignature(), head.mineskinUuid()));
                }
            }
        }

        // Build chunk → entry map for this block. Only chunks whose head
        // actually got a MineSkin texture are written — otherwise the
        // runtime listener would think the block is supported and spawn
        // FakeBlocks with zero entities (visually a no-op).
        TreeMap<ChunkCoord, HeadsRegistry.Entry> chunkToEntry = new TreeMap<>();
        packed.chunkToHead().forEach((chunk, head) -> {
            if (head.state() == SkinState.COMPLETED) {
                HeadsRegistry.Entry entry = state.skinByHash.get(head.contentHash());
                if (entry != null) chunkToEntry.put(chunk.coord(), entry);
            }
        });
        if (chunkToEntry.isEmpty()) {
            logger.info("[" + key + "] no completed skins; not writing a block entry");
            state.blocks.remove(key);
        } else {
            // Capture per-variant rotation hints alongside the chunk map so
            // the runtime can orient oak_log[axis=x] etc. correctly without
            // re-parsing the blockstate JSON. Identity-rotation variants
            // (canonical orientation) are dropped — the runtime falls back
            // to identity for unknown variant keys.
            TreeMap<String, VariantRotation> variantMap = new TreeMap<>();
            for (Map.Entry<String, VariantRotation> e : model.variantRotations().entrySet()) {
                if (e.getValue().xDeg() != 0 || e.getValue().yDeg() != 0) {
                    variantMap.put(e.getKey(), e.getValue());
                }
            }
            state.blocks.put(key, new HeadsJsonCodec.Block(chunkToEntry, variantMap));
        }
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

    /**
     * Mutable bake-side projection of a {@link HeadsJsonCodec.Document}. Holds
     * the in-progress block table plus a hash-indexed view of every skin
     * referenced by it, so {@link #bakeOne} can dedup uploads against prior
     * runs. {@link #write} re-derives the document and serializes via the
     * shared codec.
     */
    static final class BakeState {
        String version;
        int gridN;
        final Map<BlockKey, HeadsJsonCodec.Block> blocks = new TreeMap<>(
                java.util.Comparator.comparing(BlockKey::asString));
        final Map<String, HeadsRegistry.Entry> skinByHash = new LinkedHashMap<>();

        BakeState(String version, int gridN) {
            this.version = version;
            this.gridN = gridN;
        }

        static BakeState loadOrEmpty(Path out, String version, int gridN, Logger logger) throws IOException {
            BakeState s = new BakeState(version, gridN);
            if (!Files.isRegularFile(out)) return s;
            String json = Files.readString(out, StandardCharsets.UTF_8);
            HeadsJsonCodec.Document<BlockKey> doc = HeadsJsonCodec.read(
                    json, BlockKey::of, gridN, version, logger);
            s.version = doc.version();
            s.gridN = doc.gridN();
            for (Map.Entry<BlockKey, HeadsJsonCodec.Block> be : doc.blocks().entrySet()) {
                s.blocks.put(be.getKey(), be.getValue());
                for (HeadsRegistry.Entry e : be.getValue().chunks().values()) {
                    s.skinByHash.putIfAbsent(e.skinHash(), e);
                }
            }
            return s;
        }

        void write(Path out) throws IOException {
            String json = HeadsJsonCodec.write(
                    new HeadsJsonCodec.Document<>(version, gridN, blocks),
                    BlockKey::asString);
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, json, StandardCharsets.UTF_8);
        }
    }
}
