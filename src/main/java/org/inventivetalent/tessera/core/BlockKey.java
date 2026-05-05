package org.inventivetalent.tessera.core;

import java.util.Locale;
import java.util.Objects;

/**
 * Namespaced block identifier — e.g. {@code minecraft:stone}. Used as the
 * stable lookup key for asset resolution, head packing, and the heads.json
 * registry. Insulates downstream code from {@code org.bukkit.Material} so the
 * splitting/skin pipeline can run in the bake task (which has no Bukkit
 * runtime).
 */
public record BlockKey(String namespace, String path) {

    public BlockKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.isEmpty() || path.isEmpty()) {
            throw new IllegalArgumentException("namespace and path must be non-empty");
        }
    }

    public static BlockKey of(String namespaced) {
        String s = namespaced.toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon < 0) return new BlockKey("minecraft", s);
        return new BlockKey(s.substring(0, colon), s.substring(colon + 1));
    }

    public String asString() {
        return namespace + ":" + path;
    }

    @Override
    public String toString() {
        return asString();
    }
}
