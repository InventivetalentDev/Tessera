package io.tessera.plugin;

import io.tessera.assemble.FakeBlockFactory;
import io.tessera.assemble.HeadItemFactory;
import io.tessera.skin.HeadsRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class TesseraPlugin extends JavaPlugin {

    private TesseraConfig config;
    private HeadsRegistry registry;
    private HeadItemFactory itemFactory;
    private FakeBlockFactory blockFactory;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = TesseraConfig.from(getConfig());

        String mcVersion = Bukkit.getMinecraftVersion();
        this.registry = HeadsRegistry.loadFromClasspath(
                getLogger(), "/heads.json", config.chunkGridSize(), mcVersion);
        this.itemFactory = new HeadItemFactory();
        this.blockFactory = new FakeBlockFactory(itemFactory, registry);

        getServer().getPluginManager().registerEvents(
                new BlockBreakListener(this, blockFactory, registry), this);

        PluginCommand cmd = getCommand("tessera");
        if (cmd != null) cmd.setExecutor(new TesseraCommand(this, blockFactory, registry));

        getLogger().info("Tessera enabled: gridN=" + config.chunkGridSize()
                + ", mcVersion=" + mcVersion
                + ", mineskin=" + (config.mineskinApiKey().isBlank() ? "off" : "configured"));
    }

    @Override
    public void onDisable() {
        if (itemFactory != null) itemFactory.clear();
        getLogger().info("Tessera disabled");
    }

    public TesseraConfig tesseraConfig() {
        return config;
    }

    public void reloadTesseraConfig() {
        reloadConfig();
        this.config = TesseraConfig.from(getConfig());
    }
}
