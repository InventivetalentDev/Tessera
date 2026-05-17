package org.inventivetalent.tessera.plugin;

import org.inventivetalent.tessera.assemble.FakeBlockFactory;
import org.inventivetalent.tessera.assemble.HeadItemFactory;
import org.inventivetalent.tessera.assets.fetch.McAssetClient;
import org.inventivetalent.tessera.nms.BlockTintReader;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.inventivetalent.tessera.skin.SkinDiskCache;
import org.inventivetalent.tessera.skin.SkinUploader;
import org.inventivetalent.tessera.skin.bake.BlockBaker;
import org.inventivetalent.tessera.skin.store.AddonPackLoader;
import org.inventivetalent.tessera.skin.store.HeadsStore;
import org.inventivetalent.tessera.skin.store.JsonMigrator;
import org.inventivetalent.tessera.skin.store.LayeredHeadsStore;
import org.inventivetalent.tessera.skin.store.TsraFolderStore;
import org.inventivetalent.tessera.skin.store.TsraFormat;
import org.inventivetalent.tessera.skin.store.TsraZipStore;
import org.inventivetalent.tessera.util.Hashing;
import org.inventivetalent.tessera.transport.DisplayTransport;
import org.inventivetalent.tessera.transport.bukkit.BukkitDisplayTransport;
import org.inventivetalent.tessera.transport.packet.PacketDisplayTransport;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class TesseraPlugin extends JavaPlugin {

    private TesseraConfig config;
    private HeadsRegistry registry;
    private LayeredHeadsStore headsStore;
    private HeadItemFactory itemFactory;
    private FakeBlockFactory blockFactory;
    private SkinUploader uploader;
    private SkinDiskCache diskCache;
    private BlockBaker baker;
    private java.util.concurrent.ExecutorService bakerExecutor;
    private BlockBreakProgressListener progressListener;
    private BackendClient backendClient;
    private Path addonsDir;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = TesseraConfig.from(getConfig());

        String mcVersion = Bukkit.getMinecraftVersion();
        int gridN = config.chunkGridSize();

        // Heads catalogs stack from most- to least-specific:
        //   1. plugins/Tessera/cache/heads-{gridN}/   (writable runtime store)
        //   2. plugins/Tessera/heads/*.ztsra          (admin-supplied addon packs)
        //   3. /heads-{gridN}.ztsra (jar resource)    (bundled with the plugin)
        // Reads fall through in that order so addon packs add to (or
        // override) the bundled jar while runtime bakes always win. Writes
        // go to layer 1; the jar resource and addon files are immutable.
        Path cacheRoot = getDataFolder().toPath().resolve("cache");
        Path pngDir = cacheRoot.resolve("head-pngs");
        Path assetsDir = cacheRoot.resolve("assets");
        Path skinCacheFile = cacheRoot.resolve("skins.json");
        Path runtimeStoreRoot = cacheRoot.resolve("heads-" + gridN);
        this.addonsDir = getDataFolder().toPath().resolve("heads");
        try {
            Files.createDirectories(runtimeStoreRoot);
            Files.createDirectories(addonsDir);
        } catch (java.io.IOException io) {
            getLogger().warning("Failed to create store dirs: " + io.getMessage());
        }

        TsraFolderStore runtimeStore = new TsraFolderStore(getLogger(), runtimeStoreRoot);
        if (runtimeStore.manifest().isEmpty()) {
            runtimeStore.writeManifest(new TsraFormat.Manifest(gridN, mcVersion,
                    "Tessera/" + getDescription().getVersion()));
        }
        // One-shot migration from any leftover heads-{gridN}.json sitting
        // next to the runtime store. Renames to .migrated on success so
        // subsequent boots short-circuit this branch.
        Path legacyJson = cacheRoot.resolve("heads-" + gridN + ".json");
        if (Files.isRegularFile(legacyJson)) {
            int migrated = JsonMigrator.migrate(getLogger(), legacyJson, runtimeStore, gridN, mcVersion);
            if (migrated > 0) {
                try {
                    Files.move(legacyJson, legacyJson.resolveSibling(legacyJson.getFileName() + ".migrated"));
                } catch (java.io.IOException io) {
                    getLogger().warning("[tsra-migrate] failed to rename " + legacyJson + ": " + io.getMessage());
                }
            }
        }

        List<HeadsStore> initialAddons = new ArrayList<>();
        for (AddonPackLoader.LoadedAddon addon : AddonPackLoader.load(getLogger(), addonsDir, gridN)) {
            initialAddons.add(addon.store());
        }
        Optional<TsraZipStore> bundled = TsraZipStore.fromClasspath(
                getLogger(), "/heads-" + gridN + TsraFormat.ZIP_EXTENSION);
        this.headsStore = new LayeredHeadsStore(
                runtimeStore, initialAddons, bundled.orElse(null), addonsDir, gridN);

        this.registry = HeadsRegistry.loadFrom(
                getLogger(), headsStore, gridN, mcVersion, config.skinCacheCapacity());

        this.itemFactory = new HeadItemFactory();
        DisplayTransport transport = pickTransport(config);
        this.blockFactory = new FakeBlockFactory(itemFactory, registry, transport);

        // Runtime baker: kicks in when a player breaks a block we don't
        // have in the bundled heads file yet. Uses a small thread pool so
        // multiple concurrent first-time breaks don't block each other.
        //
        // Paid mode (BBB-purchased build): MineSkin traffic routes through
        // our backend with the license + identity headers attached. The
        // BackendClient handles the archive endpoints with the same headers.
        // See org.inventivetalent.tessera.plugin.Bbb for placeholder details.
        String pluginVersion = getDescription().getVersion();
        String userAgent = "Tessera/" + pluginVersion;
        if (Bbb.PAID) {
            String serverId = computeServerId();
            SkinUploader.PaidContext paid = new SkinUploader.PaidContext(
                    Bbb.BBB_LICENSE,
                    Bbb.BBB_NONCE,
                    Bbb.BBB_USER,
                    Bbb.BBB_RESOURCE,
                    pluginVersion,
                    serverId);
            this.uploader = new SkinUploader(getLogger(), userAgent, null, paid);
            this.backendClient = new BackendClient(getLogger(), userAgent, paid);
            getLogger().info("Tessera in PAID mode — routing through " + Bbb.BACKEND_BASE_URL);
        } else {
            this.uploader = new SkinUploader(getLogger(), userAgent, config.mineskinApiKey());
            this.backendClient = null;
        }
        McAssetClient assets = new McAssetClient(assetsDir);
        this.diskCache = new SkinDiskCache(getLogger(), skinCacheFile);
        this.bakerExecutor = Executors.newFixedThreadPool(2, named("Tessera-Baker"));
        this.baker = new BlockBaker(getLogger(), () -> this.config.debug(), assets, mcVersion, registry, uploader, diskCache, pngDir, bakerExecutor);

        // Inject the vanilla grass/foliage colormaps into NMS so server-side
        // Biome.getGrassColor() / getFoliageColor() return real per-biome
        // values. Without this, tinted-block bakes get tint=0 and skip
        // (server has the colormap-PNG-loading code under client-only paths).
        if (config.enableTintedBlocks()) {
            BlockTintReader.prepareColormaps(assets, mcVersion, getLogger());
        }

        AtomicInteger active = new AtomicInteger();
        ProgressTracker tracker = new ProgressTracker();
        this.progressListener = new BlockBreakProgressListener(this, blockFactory, registry, active, tracker);
        getServer().getPluginManager().registerEvents(progressListener, this);
        getServer().getPluginManager().registerEvents(
                new BlockBreakListener(this, blockFactory, registry, baker, active, progressListener), this);
        progressListener.start();

        PluginCommand cmd = getCommand("tessera");
        if (cmd != null) {
            TesseraCommand tc = new TesseraCommand(this, blockFactory, registry, baker);
            cmd.setExecutor(tc);
            cmd.setTabCompleter(tc);
        }

        // Pre-existing addon packs were loaded into the layered store before
        // the registry was built, but a runtime download via /tessera
        // archives lands files later — the registry's reindex() call walks
        // listBlocks() again to pick them up.

        getLogger().info("Tessera enabled: gridN=" + config.chunkGridSize()
                + ", mcVersion=" + mcVersion
                + ", mineskin=" + (config.mineskinApiKey().isBlank() ? "off" : "configured"));

        if (config.metrics()) new Metrics(this, 31093);
    }

    @Override
    public void onDisable() {
        if (progressListener != null) progressListener.shutdown();
        if (itemFactory != null) itemFactory.clear();
        if (uploader != null) uploader.cancelAll();
        if (bakerExecutor != null) bakerExecutor.shutdownNow();
        if (headsStore != null) headsStore.close();
        getLogger().info("Tessera disabled");
    }

    private static ThreadFactory named(String name) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public TesseraConfig tesseraConfig() {
        return config;
    }

    public void reloadTesseraConfig() {
        reloadConfig();
        this.config = TesseraConfig.from(getConfig());
    }

    /** Layered store accessor used by {@code /tessera archives} to reload addons after a download. */
    public LayeredHeadsStore headsStore() { return headsStore; }

    /** {@code null} in free mode. */
    public BackendClient backendClient() { return backendClient; }

    /** Addons directory where {@code .ztsra} packs land (both at startup and runtime downloads). */
    public Path addonsDir() { return addonsDir; }

    public HeadsRegistry registry() { return registry; }

    /**
     * Stable per-install identifier sent as {@code X-Tessera-Server-Id} so
     * the backend can flag a single license seen across many distinct
     * servers (jar copy). Derived from the absolute path of the server's
     * working directory plus the {@code server-id} line from
     * {@code server.properties}. Both inputs are stable across restarts and
     * distinct per real install; neither contains anything sensitive enough
     * to warrant transmitting in clear. We send the SHA-256 hex truncated
     * to 16 chars (64 bits — plenty to disambiguate).
     */
    private String computeServerId() {
        String workingDir = java.nio.file.Paths.get(".").toAbsolutePath().normalize().toString();
        String serverId = "";
        try {
            Path props = java.nio.file.Paths.get("server.properties");
            if (Files.isRegularFile(props)) {
                for (String line : Files.readAllLines(props)) {
                    if (line.startsWith("server-id=")) {
                        serverId = line.substring("server-id=".length()).trim();
                        break;
                    }
                }
            }
        } catch (java.io.IOException io) {
            getLogger().fine("[server-id] could not read server.properties: " + io.getMessage());
        }
        return Hashing.sha256OfStrings(List.of(workingDir, serverId)).substring(0, 16);
    }

    private DisplayTransport pickTransport(TesseraConfig cfg) {
        if (cfg.transport() == TesseraConfig.Transport.BUKKIT) {
            getLogger().info("Transport: bukkit (Paper API)");
            return new BukkitDisplayTransport(this);
        }
        PacketDisplayTransport packet = new PacketDisplayTransport();
        if (packet.isAvailable()) {
            getLogger().info("Transport: packet (NMS direct)");
            return packet;
        }
        getLogger().warning("Packet transport unavailable (reflection init failed — MC update may have"
                + " renamed Display fields). Falling back to Bukkit transport.");
        return new BukkitDisplayTransport(this);
    }
}
