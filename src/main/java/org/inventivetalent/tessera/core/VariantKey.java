package org.inventivetalent.tessera.core;

import org.bukkit.block.data.BlockData;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Translates a Bukkit {@link BlockData} into the vanilla blockstate-JSON
 * variant key format (e.g. {@code "axis=y"}, {@code "facing=west,lit=false"})
 * so {@code HeadsRegistry.rotationFor(BlockKey, String)} can pick the right
 * world-space rotation at spawn time.
 *
 * <p>Vanilla blockstate variant keys list each property as
 * {@code <name>=<value>}, alphabetically sorted, comma-separated, with no
 * spaces. {@code BlockData.getAsString()} produces a similar format but
 * (a) prefixed with the block id, (b) wrapped in square brackets, and
 * (c) including <em>all</em> properties — most of which (like
 * {@code waterlogged}) don't actually change which variant is rendered.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Strip the {@code minecraft:<id>} prefix and {@code [...]} brackets
 *       to get the raw {@code k=v,k=v} list.</li>
 *   <li>Try the full property set first.</li>
 *   <li>If no match, try removing properties one at a time (preferring
 *       known "irrelevant" ones like {@code waterlogged}) until a match is
 *       found or only the empty key remains. The variant lookup is cheap;
 *       brute force is fine.</li>
 * </ol>
 *
 * <p>Returns the <em>full</em> stripped key as a starting point;
 * {@link #pickMatching} does the iterative narrowing against an actual
 * variant set.
 */
public final class VariantKey {

    /**
     * Properties that vanilla blockstates almost never branch on. Tried
     * first when narrowing a state string against a variant map.
     */
    private static final Set<String> NUISANCE_PROPERTIES = Set.of(
            "waterlogged", "powered", "triggered", "occupied", "open",
            "in_wall", "snowy", "stage", "level", "leaves", "distance",
            "persistent", "age");

    private VariantKey() {}

    /**
     * Convert a {@code BlockData} into a vanilla-format variant key with
     * <em>every</em> property included. Properties are alphabetically
     * sorted; values lower-cased. Returns {@code ""} for blocks with no
     * properties.
     */
    public static String fromBlockData(BlockData data) {
        String full = data.getAsString();
        int lb = full.indexOf('[');
        if (lb < 0) return "";
        int rb = full.lastIndexOf(']');
        String inner = full.substring(lb + 1, rb >= 0 ? rb : full.length());
        if (inner.isEmpty()) return "";
        // Already comma-separated and (per Paper's BlockData impl) sorted,
        // but normalize defensively in case some implementation differs.
        TreeMap<String, String> kv = new TreeMap<>();
        for (String part : inner.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            kv.put(part.substring(0, eq), part.substring(eq + 1));
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * Find the variant key in {@code candidates} that best matches the
     * full property set from {@link #fromBlockData}. Tries the full key
     * first, then progressively drops nuisance properties, then drops any
     * remaining property until a match is found. Returns {@code ""} if no
     * match — caller should treat as identity rotation.
     */
    public static String pickMatching(String fullKey, Set<String> candidates) {
        if (fullKey == null || candidates.isEmpty()) return "";
        if (candidates.contains(fullKey)) return fullKey;
        TreeMap<String, String> kv = parseKey(fullKey);

        // Drop nuisance properties first.
        for (String nuisance : NUISANCE_PROPERTIES) {
            if (kv.remove(nuisance) != null) {
                String trimmed = serialize(kv);
                if (candidates.contains(trimmed)) return trimmed;
            }
        }
        // Drop remaining properties one at a time until a match.
        // Prefer to drop later (alphabetically) properties first since
        // earlier ones (axis, facing) are usually the variant pivot.
        java.util.List<String> keys = new java.util.ArrayList<>(kv.keySet());
        java.util.Collections.reverse(keys);
        for (String k : keys) {
            kv.remove(k);
            String trimmed = serialize(kv);
            if (candidates.contains(trimmed)) return trimmed;
        }
        return "";
    }

    private static TreeMap<String, String> parseKey(String key) {
        TreeMap<String, String> out = new TreeMap<>();
        if (key.isEmpty()) return out;
        for (String part : key.split(",")) {
            int eq = part.indexOf('=');
            if (eq >= 0) out.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return out;
    }

    private static String serialize(TreeMap<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
