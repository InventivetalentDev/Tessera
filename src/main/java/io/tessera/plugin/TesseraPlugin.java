package io.tessera.plugin;

import io.tessera.assemble.FakeBlockFactory;
import io.tessera.assemble.HeadItemFactory;
import io.tessera.assets.fetch.McAssetClient;
import io.tessera.skin.HeadsRegistry;
import io.tessera.skin.SkinDiskCache;
import io.tessera.skin.SkinUploader;
import io.tessera.skin.bake.BlockBaker;
import io.tessera.skin.bake.RuntimeHeadsStore;
import io.tessera.transport.DisplayTransport;
import io.tessera.transport.bukkit.BukkitDisplayTransport;
import io.tessera.transport.packet.PacketDisplayTransport;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class TesseraPlugin extends JavaPlugin {

    private TesseraConfig config;
    private HeadsRegistry registry;
    private HeadItemFactory itemFactory;
    private FakeBlockFactory blockFactory;
    private SkinUploader uploader;
    private SkinDiskCache diskCache;
    private BlockBaker baker;
    private java.util.concurrent.ExecutorService bakerExecutor;
    private BlockBreakProgressListener progressListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = TesseraConfig.from(getConfig());

        String mcVersion = Bukkit.getMinecraftVersion();
        // Bundled heads file is per-grid-size: heads-4.json, heads-8.json, …
        // If no file exists for the configured size, the registry starts
        // empty and runtime baking populates it (requires mineskinApiKey).
        int gridN = config.chunkGridSize();
        this.registry = HeadsRegistry.loadFromClasspath(
                getLogger(), "/heads-" + gridN + ".json", gridN, mcVersion);
        this.itemFactory = new HeadItemFactory();
        DisplayTransport transport = pickTransport(config);
        this.blockFactory = new FakeBlockFactory(itemFactory, registry, transport);

        // Runtime baker: kicks in when a player breaks a block we don't
        // have in the bundled heads file yet. Uses a small thread pool so
        // multiple concurrent first-time breaks don't block each other.
        this.uploader = new SkinUploader(
                getLogger(), "Tessera/" + getDescription().getVersion(), config.mineskinApiKey());
        Path cacheRoot = getDataFolder().toPath().resolve("cache");
        Path pngDir = cacheRoot.resolve("heads");
        Path assetsDir = cacheRoot.resolve("assets");
        Path skinCacheFile = cacheRoot.resolve("skins.json");
        Path runtimeHeadsFile = cacheRoot.resolve("heads-" + gridN + ".json");
        McAssetClient assets = new McAssetClient(assetsDir, getLogger());
        this.diskCache = new SkinDiskCache(getLogger(), skinCacheFile);

        // Rehydrate runtime-baked entries from previous sessions, then attach
        // the same store as a persistence sink so future bakes / invalidations
        // are mirrored to disk.
        RuntimeHeadsStore runtimeHeads = new RuntimeHeadsStore(
                getLogger(), runtimeHeadsFile, registry.gridN(), registry.version());
        runtimeHeads.loadInto(registry);
        registry.setPersistence(runtimeHeads);
        this.bakerExecutor = Executors.newFixedThreadPool(2, named("Tessera-Baker"));
        this.baker = new BlockBaker(getLogger(), () -> this.config.debug(), assets, mcVersion, registry, uploader, diskCache, pngDir, bakerExecutor);

        // Inject the vanilla grass/foliage colormaps into NMS so server-side
        // Biome.getGrassColor() / getFoliageColor() return real per-biome
        // values. Without this, tinted-block bakes get tint=0 and skip
        // (server has the colormap-PNG-loading code under client-only paths).
        if (config.enableTintedBlocks()) {
            io.tessera.nms.BlockTintReader.prepareColormaps(assets, mcVersion, getLogger());
        }

        AtomicInteger active = new AtomicInteger();
        ProgressTracker tracker = new ProgressTracker();
        this.progressListener = new BlockBreakProgressListener(this, blockFactory, registry, active, tracker);
        getServer().getPluginManager().registerEvents(progressListener, this);
        getServer().getPluginManager().registerEvents(
                new BlockBreakListener(this, blockFactory, registry, baker, active, tracker, progressListener), this);
        progressListener.start();

        PluginCommand cmd = getCommand("tessera");
        if (cmd != null) cmd.setExecutor(new TesseraCommand(this, blockFactory, registry, baker));

        getLogger().info("Tessera enabled: gridN=" + config.chunkGridSize()
                + ", mcVersion=" + mcVersion
                + ", mineskin=" + (config.mineskinApiKey().isBlank() ? "off" : "configured"));
    }

    @Override
    public void onDisable() {
        if (progressListener != null) progressListener.shutdown();
        if (itemFactory != null) itemFactory.clear();
        if (uploader != null) uploader.cancelAll();
        if (bakerExecutor != null) bakerExecutor.shutdownNow();
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
