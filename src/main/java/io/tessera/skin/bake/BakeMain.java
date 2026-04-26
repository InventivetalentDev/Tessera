package io.tessera.skin.bake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.tessera.assets.fetch.McAssetClient;
import io.tessera.assets.model.BlockModel;
import io.tessera.assets.model.ModelResolver;
import io.tessera.core.BlockKey;
import io.tessera.core.ChunkSpec;
import io.tessera.skin.HeadSkin;
import io.tessera.skin.HeadSkinPacker;
import io.tessera.skin.SkinAssembler;
import io.tessera.skin.SkinState;
import io.tessera.skin.SkinUploader;
import io.tessera.split.TextureSplitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
 * MineSkin, and writes a deterministic {@code heads.json}.
 *
 * <p>Idempotent: skip already-baked entries by hash, so re-running on the
 * same input is a no-op once everything has been uploaded once.
 *
 * <p>Args:
 * <pre>
 *   --input    bake-blocks.txt (one block ID per line, # comments)
 *   --out      heads.json output path
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
        Path outPath    = Path.of(args.getOrDefault("out",    "src/main/resources/heads.json"));
        Path cacheRoot  = Path.of(args.getOrDefault("cache",  "build/tessera-cache"));
        String version  = args.getOrDefault("version", "1.21.4");
        int gridN       = Integer.parseInt(args.getOrDefault("gridN", "4"));

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

        // Existing heads.json state, indexed by skinHash → entry, so we can
        // skip uploads we already ran. Idempotent reruns are the goal.
        BakeState state = BakeState.loadOrEmpty(outPath, version, gridN);
        if (state.gridN != gridN || !state.version.equals(version)) {
            logger.warning("heads.json was baked with version=" + state.version + " gridN=" + state.gridN
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
        logger.info("Done. heads.json written to " + outPath.toAbsolutePath());
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
            if (state.skinByHash.containsKey(head.contentHash())) {
                BakeState.Skin existing = state.skinByHash.get(head.contentHash());
                head.texture(existing.value, existing.signature, existing.mineskinUuid);
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
                    state.skinByHash.put(head.contentHash(), new BakeState.Skin(
                            head.contentHash(), head.textureValue(), head.textureSignature(), head.mineskinUuid()));
                }
            }
        }

        // Build chunk → hash map for this block. Only chunks whose head
        // actually got a MineSkin texture are written — otherwise the
        // runtime listener would think the block is supported and spawn
        // FakeBlocks with zero entities (visually a no-op).
        TreeMap<String, String> chunkToHash = new TreeMap<>();
        packed.chunkToHead().forEach((chunk, head) -> {
            if (head.state() == SkinState.COMPLETED) {
                chunkToHash.put(chunk.coord().asKey(), head.contentHash());
            }
        });
        if (chunkToHash.isEmpty()) {
            logger.info("[" + key + "] no completed skins; not writing a block entry");
            state.blocks.remove(key.asString());
        } else {
            state.blocks.put(key.asString(), chunkToHash);
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

    static final class BakeState {
        String version;
        int gridN;
        TreeMap<String, TreeMap<String, String>> blocks = new TreeMap<>();
        TreeMap<String, Skin> skinByHash = new TreeMap<>();

        BakeState(String version, int gridN) {
            this.version = version;
            this.gridN = gridN;
        }

        record Skin(String hash, String value, String signature, String mineskinUuid) {}

        static BakeState loadOrEmpty(Path out, String version, int gridN) throws IOException {
            BakeState s = new BakeState(version, gridN);
            if (!Files.isRegularFile(out)) return s;
            String json = Files.readString(out, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (root.has("version")) s.version = root.get("version").getAsString();
            if (root.has("gridN"))   s.gridN   = root.get("gridN").getAsInt();
            if (root.has("skins")) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("skins").entrySet()) {
                    JsonObject sk = e.getValue().getAsJsonObject();
                    s.skinByHash.put(e.getKey(), new Skin(
                            e.getKey(),
                            sk.get("value").getAsString(),
                            sk.get("signature").getAsString(),
                            sk.has("mineskinUuid") ? sk.get("mineskinUuid").getAsString() : null));
                }
            }
            if (root.has("blocks")) {
                for (Map.Entry<String, JsonElement> b : root.getAsJsonObject("blocks").entrySet()) {
                    TreeMap<String, String> chunks = new TreeMap<>();
                    for (Map.Entry<String, JsonElement> c : b.getValue().getAsJsonObject().entrySet()) {
                        String hash = c.getValue().getAsJsonObject().get("skinHash").getAsString();
                        chunks.put(c.getKey(), hash);
                    }
                    s.blocks.put(b.getKey(), chunks);
                }
            }
            return s;
        }

        void write(Path out) throws IOException {
            JsonObject root = new JsonObject();
            root.addProperty("version", version);
            root.addProperty("gridN", gridN);

            JsonObject skinsJson = new JsonObject();
            for (Skin sk : skinByHash.values()) {
                JsonObject so = new JsonObject();
                so.addProperty("value", sk.value);
                so.addProperty("signature", sk.signature);
                if (sk.mineskinUuid != null) so.addProperty("mineskinUuid", sk.mineskinUuid);
                skinsJson.add(sk.hash, so);
            }
            root.add("skins", skinsJson);

            JsonObject blocksJson = new JsonObject();
            // Sort blocks alphabetically — chunk lists already TreeMap-sorted.
            blocks.entrySet().stream()
                    .sorted(Comparator.comparing(Map.Entry::getKey))
                    .forEach(entry -> {
                        JsonObject block = new JsonObject();
                        for (Map.Entry<String, String> c : entry.getValue().entrySet()) {
                            JsonObject chunk = new JsonObject();
                            chunk.addProperty("skinHash", c.getValue());
                            block.add(c.getKey(), chunk);
                        }
                        blocksJson.add(entry.getKey(), block);
                    });
            root.add("blocks", blocksJson);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.createDirectories(out.toAbsolutePath().getParent());
            Files.writeString(out, gson.toJson(root), StandardCharsets.UTF_8);
        }
    }
}
