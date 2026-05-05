package org.inventivetalent.tessera.plugin;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Snapshot of the on-disk config. Built once per load via {@link #from(FileConfiguration)}
 * and replaced wholesale on {@code /tessera reload} — never mutated in place, so callers
 * holding a reference always see a coherent set of values.
 *
 * <p>The on-disk layout uses nested sections (e.g. {@code animation.mode},
 * {@code progress.minDelta}); legacy flat keys are still accepted as a
 * fallback so existing configs keep working until users migrate.
 */
public record TesseraConfig(
        String mineskinApiKey,
        int chunkGridSize,
        Set<String> enabledMaterials,
        Set<String> disabledMaterials,
        int maxConcurrentFakeBlocks,
        AnimationMode animationMode,
        CollapseStyle collapseStyle,
        int effectDurationMs,
        double waveWindow,
        boolean fillInterior,
        boolean clientHideRealBlock,
        double progressMinDelta,
        boolean smoothInterpolation,
        boolean startOnLeftClick,
        long leftClickGraceMs,
        boolean eagerPreload,
        int minBreakDurationMs,
        boolean enableTintedBlocks,
        boolean metrics,
        boolean debug,
        Transport transport
) {

    public enum AnimationMode { PROGRESS, POST_BREAK }

    /**
     * How a chunk transitions out as the wave reaches it.
     * <ul>
     *   <li>{@link #SHRINK} — chunk uniformly scales toward 0 (default).</li>
     *   <li>{@link #POP} — chunk stays at full size until the wave hits it,
     *       then disappears in one tick. Visually "crumbling" rather than
     *       "shrinking".</li>
     * </ul>
     */
    public enum CollapseStyle { SHRINK, POP }

    /**
     * Entity transport backend.
     * <ul>
     *   <li>{@link #PACKET} — send raw clientbound packets directly to the breaker.
     *       No server-side entity tracking; zero impact on world entity lists.
     *       Default and recommended.</li>
     *   <li>{@link #BUKKIT} — use the standard Paper API ({@code World.spawn}).
     *       Fallback for MC updates that break the packet path.</li>
     * </ul>
     */
    public enum Transport { PACKET, BUKKIT }

    public static TesseraConfig from(FileConfiguration cfg) {
        int grid = readInt(cfg, "chunkGridSize", 4);
        if (grid < 1 || grid > 16 || 16 % grid != 0) {
            throw new IllegalArgumentException(
                    "chunkGridSize must be one of 1, 2, 4, 8, 16; got " + grid
                            + ". Note: each grid size loads its own heads-<N>.json"
                            + " file — if no bundled file exists for the chosen"
                            + " size, blocks are re-baked on demand via MineSkin.");
        }

        AnimationMode mode = parseAnimationMode(
                readString(cfg, "animation.mode", "animationMode", "progress"));
        CollapseStyle style = parseCollapseStyle(
                readString(cfg, "animation.style", "collapseStyle", "pop"));

        Transport transport = parseTransport(
                readString(cfg, "transport", "transport", "packet"));

        return new TesseraConfig(
                readString(cfg, "mineskin.apiKey", "mineskinApiKey", ""),
                grid,
                normalize(readStringList(cfg, "materials.enabled", "enabledMaterials", List.of("*"))),
                normalize(readStringList(cfg, "materials.disabled", "disabledMaterials", List.of(
                        "minecraft:water", "minecraft:lava", "minecraft:fire", "minecraft:soul_fire"))),
                readInt(cfg, "limits.maxConcurrentFakeBlocks", "maxConcurrentFakeBlocks", 8),
                mode,
                style,
                readInt(cfg, "animation.durationMs", "effectDurationMs", 600),
                readDouble(cfg, "animation.waveWindow", "waveWindow", 0.25d),
                readBool(cfg, "animation.fillInterior", "fillInterior", false),
                readBool(cfg, "progress.clientHideRealBlock", "clientHideRealBlock", true),
                readDouble(cfg, "progress.minDelta", "progressMinDelta", 0.02d),
                readBool(cfg, "progress.smoothInterpolation", "smoothInterpolation", true),
                readBool(cfg, "interaction.startOnLeftClick", "startOnLeftClick", true),
                readLong(cfg, "interaction.leftClickGraceMs", "leftClickGraceMs", 500L),
                readBool(cfg, "interaction.eagerPreload", "eagerPreload", false),
                readInt(cfg, "interaction.minBreakDurationMs", "minBreakDurationMs", 500),
                cfg.getBoolean("enableTintedBlocks", true),
                cfg.getBoolean("metrics", true),
                readBool(cfg, "debug", "debug", false),
                transport
        );
    }

    public boolean enables(String materialKey) {
        String key = materialKey.toLowerCase(Locale.ROOT);
        if (disabledMaterials.contains(key)) return false;
        return enabledMaterials.contains("*") || enabledMaterials.contains(key);
    }

    private static AnimationMode parseAnimationMode(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "post-break", "post_break", "postbreak" -> AnimationMode.POST_BREAK;
            case "progress" -> AnimationMode.PROGRESS;
            default -> throw new IllegalArgumentException(
                    "animation.mode must be 'progress' or 'post-break'; got " + raw);
        };
    }

    private static CollapseStyle parseCollapseStyle(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "shrink" -> CollapseStyle.SHRINK;
            case "pop", "instant", "disappear" -> CollapseStyle.POP;
            default -> throw new IllegalArgumentException(
                    "animation.style must be 'shrink' or 'pop'; got " + raw);
        };
    }

    private static Transport parseTransport(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "packet", "nms" -> Transport.PACKET;
            case "bukkit", "api" -> Transport.BUKKIT;
            default -> throw new IllegalArgumentException(
                    "transport must be 'packet' or 'bukkit'; got " + raw);
        };
    }

    private static String readString(FileConfiguration cfg, String path, String legacy, String def) {
        if (cfg.isSet(path)) return cfg.getString(path, def);
        return cfg.getString(legacy, def);
    }

    private static int readInt(FileConfiguration cfg, String path, String legacy, int def) {
        if (cfg.isSet(path)) return cfg.getInt(path, def);
        return cfg.getInt(legacy, def);
    }

    private static int readInt(FileConfiguration cfg, String path, int def) {
        return cfg.getInt(path, def);
    }

    private static long readLong(FileConfiguration cfg, String path, String legacy, long def) {
        if (cfg.isSet(path)) return cfg.getLong(path, def);
        return cfg.getLong(legacy, def);
    }

    private static double readDouble(FileConfiguration cfg, String path, String legacy, double def) {
        if (cfg.isSet(path)) return cfg.getDouble(path, def);
        return cfg.getDouble(legacy, def);
    }

    private static boolean readBool(FileConfiguration cfg, String path, String legacy, boolean def) {
        if (cfg.isSet(path)) return cfg.getBoolean(path, def);
        return cfg.getBoolean(legacy, def);
    }

    private static List<String> readStringList(FileConfiguration cfg, String path, String legacy,
                                               List<String> def) {
        if (cfg.isSet(path)) return cfg.getStringList(path);
        if (cfg.isSet(legacy)) return cfg.getStringList(legacy);
        return def;
    }

    private static Set<String> normalize(List<String> raw) {
        return raw.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                // Don't namespace the wildcard - "*" must stay literal so
                // enables() can match it. Anything else without a colon
                // gets the vanilla namespace.
                .map(s -> s.equals("*") || s.contains(":") ? s : "minecraft:" + s)
                .collect(Collectors.toUnmodifiableSet());
    }
}
