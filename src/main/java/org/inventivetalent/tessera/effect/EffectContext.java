package org.inventivetalent.tessera.effect;

import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Per-application input to a {@link ChunkEffect}. Carries the breaker's view
 * direction (used to bias wave-direction effects), timing parameters, and
 * the plugin reference (needed for Bukkit scheduler access).
 */
public record EffectContext(Vector breakerEyeDir, long startTickMs, int durationMs, Plugin plugin) {
    public EffectContext {
        breakerEyeDir = breakerEyeDir.clone().normalize();
    }
}
