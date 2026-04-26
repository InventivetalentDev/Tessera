package io.tessera.plugin;

import io.tessera.assemble.FakeBlockFactory;
import io.tessera.assemble.HeadItemFactory;
import io.tessera.assets.fetch.McAssetClient;
import io.tessera.skin.HeadsRegistry;
import io.tessera.skin.SkinUploader;
import io.tessera.skin.bake.BlockBaker;
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
    private BlockBaker baker;
    private java.util.concurrent.ExecutorService bakerExecutor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = TesseraConfig.from(getConfig());

        String mcVersion = Bukkit.getMinecraftVersion();
        this.registry = HeadsRegistry.loadFromClasspath(
                getLogger(), "/heads.json", config.chunkGridSize(), mcVersion);
        this.itemFactory = new HeadItemFactory();
        this.blockFactory = new FakeBlockFactory(itemFactory, registry);

        // Runtime baker: kicks in when a player breaks a block we don't
        // have in heads.json yet. Uses a small thread pool so multiple
        // concurrent first-time breaks don't block each other.
        this.uploader = new SkinUploader(
                getLogger(), "Tessera/" + getDescription().getVersion(), config.mineskinApiKey());
        Path cacheRoot = getDataFolder().toPath().resolve("cache");
        Path pngDir = cacheRoot.resolve("heads");
        Path assetsDir = cacheRoot.resolve("assets");
        McAssetClient assets = new McAssetClient(assetsDir, getLogger());
        this.bakerExecutor = Executors.newFixedThreadPool(2, named("Tessera-Baker"));
        this.baker = new BlockBaker(getLogger(), assets, mcVersion, registry, uploader, pngDir, bakerExecutor);

        getServer().getPluginManager().registerEvents(
                new BlockBreakListener(this, blockFactory, registry, baker), this);

        PluginCommand cmd = getCommand("tessera");
        if (cmd != null) cmd.setExecutor(new TesseraCommand(this, blockFactory, registry, baker));

        getLogger().info("Tessera enabled: gridN=" + config.chunkGridSize()
                + ", mcVersion=" + mcVersion
                + ", mineskin=" + (config.mineskinApiKey().isBlank() ? "off" : "configured"));
    }

    @Override
    public void onDisable() {
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
}
