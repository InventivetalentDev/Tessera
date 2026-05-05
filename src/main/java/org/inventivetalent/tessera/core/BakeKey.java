package org.inventivetalent.tessera.core;

import java.util.Objects;

/**
 * A {@link BlockKey} plus optional 24-bit ARGB tint, used as the registry /
 * cache identity for a baked block. {@code tintArgb == 0} is the sentinel for
 * "untinted" and matches the historical {@link BlockKey}-only behaviour;
 * non-zero values carry the resolved biome tint multiplier (alpha forced to
 * {@code 0xFF} by {@code BlockTintReader} so the value is stable).
 *
 * <p>Tinted bakes are runtime-only — {@code heads.json} on disk is keyed by
 * {@link BlockKey} and parsed back as {@code BakeKey.untinted(...)}. Different
 * tints of the same block produce different post-paint PNG hashes, so the
 * existing {@code SkinDiskCache} dedupes uploads naturally without any other
 * cache plumbing.
 */
public record BakeKey(BlockKey block, int tintArgb) {

    public BakeKey {
        Objects.requireNonNull(block, "block");
    }

    public static BakeKey untinted(BlockKey block) {
        return new BakeKey(block, 0);
    }

    public boolean isTinted() {
        return tintArgb != 0;
    }

    @Override
    public String toString() {
        if (tintArgb == 0) return block.toString();
        return block + "#" + String.format("%06x", tintArgb & 0xFFFFFF);
    }

    /**
     * Inverse of {@link #toString()}. Accepts {@code "minecraft:stone"} →
     * untinted and {@code "minecraft:oak_leaves#7fbf2e"} → tinted with the
     * upper byte forced to {@code 0xFF} (matching {@code BlockTintReader}'s
     * stable-alpha convention).
     */
    public static BakeKey parse(String s) {
        int hash = s.indexOf('#');
        if (hash < 0) return untinted(BlockKey.of(s));
        BlockKey block = BlockKey.of(s.substring(0, hash));
        int rgb = Integer.parseInt(s.substring(hash + 1), 16);
        return new BakeKey(block, 0xFF000000 | (rgb & 0xFFFFFF));
    }
}
