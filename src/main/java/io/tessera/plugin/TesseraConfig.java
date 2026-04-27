package io.tessera.plugin;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Snapshot of the on-disk config. Built once per load via {@link #from(FileConfiguration)}
 * and replaced wholesale on {@code /tessera reload} — never mutated in place, so callers
 * holding a reference always see a coherent set of values.
 */
public record TesseraConfig(
        String mineskinApiKey,
        int chunkGridSize,
        Set<String> enabledMaterials,
        Set<String> disabledMaterials,
        int effectDurationMs,
        int maxConcurrentFakeBlocks,
        AnimationMode animationMode,
        boolean clientHideRealBlock,
        double waveWindow,
        double progressMinDelta,
        boolean debug
) {

    public enum AnimationMode { PROGRESS, POST_BREAK }

    public static TesseraConfig from(FileConfiguration cfg) {
        int grid = cfg.getInt("chunkGridSize", 4);
        if (16 % grid != 0) {
            throw new IllegalArgumentException(
                    "chunkGridSize must divide 16; got " + grid);
        }
        String modeRaw = cfg.getString("animationMode", "progress");
        AnimationMode mode = switch (modeRaw.toLowerCase(Locale.ROOT)) {
            case "post-break", "post_break", "postbreak" -> AnimationMode.POST_BREAK;
            case "progress" -> AnimationMode.PROGRESS;
            default -> throw new IllegalArgumentException(
                    "animationMode must be 'progress' or 'post-break'; got " + modeRaw);
        };
        return new TesseraConfig(
                cfg.getString("mineskinApiKey", ""),
                grid,
                normalize(cfg.getStringList("enabledMaterials")),
                normalize(cfg.getStringList("disabledMaterials")),
                cfg.getInt("effectDurationMs", 600),
                cfg.getInt("maxConcurrentFakeBlocks", 8),
                mode,
                cfg.getBoolean("clientHideRealBlock", false),
                cfg.getDouble("waveWindow", 0.25d),
                cfg.getDouble("progressMinDelta", 0.02d),
                cfg.getBoolean("debug", false)
        );
    }

    public boolean enables(String materialKey) {
        String key = materialKey.toLowerCase(Locale.ROOT);
        if (disabledMaterials.contains(key)) return false;
        return enabledMaterials.contains("*") || enabledMaterials.contains(key);
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
