package org.inventivetalent.tessera.skin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.inventivetalent.tessera.util.Hashing;
import org.inventivetalent.tessera.skin.bake.BlockBaker;
import org.inventivetalent.tessera.split.SourceRotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Persistent disk cache mapping a packed-PNG content hash to the MineSkin
 * texture value/signature returned the last time we uploaded those exact
 * bytes. Survives plugin reloads and restarts.
 *
 * <p>Why hash the PNG bytes (post-paint) and not the pre-paint dedup hash
 * already in {@link HeadSkin#contentHash()}? The pre-paint hash describes
 * the TILE content before any of the rotation/flip knobs are applied;
 * changing {@link TileRotations} or
 * {@link SourceRotations} produces a different PNG with
 * the same pre-paint hash. We need each distinct PNG to map to its own
 * MineSkin upload.
 *
 * <p>This is content-addressed, so identical configurations across blocks
 * dedupe naturally — e.g. all 24 face-center chunks of a uniform stone
 * block produce the same PNG, hash, and cache entry, regardless of which
 * block they came from. Switching between two pre-baked configurations
 * (e.g. {@code sourcerot east 0} ↔ {@code sourcerot east 90}) is then
 * instant once both have been uploaded once.
 *
 * <p>File format ({@code plugins/Tessera/cache/skins.json}):
 * <pre>{@code
 * {
 *   "skins": {
 *     "<sha256-of-png-bytes>": {
 *       "value": "ey...", "signature": "...", "uuid": "...", "addedAt": 1700000000
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Writes are atomic (temp file + rename) so a kill mid-write doesn't
 * corrupt the file. In-memory map is a {@link ConcurrentHashMap} since
 * {@link BlockBaker} runs on a worker pool.
 */
public final class SkinDiskCache {

    public record Entry(String value, String signature, String uuid, long addedAt) {}

    private final Logger logger;
    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public SkinDiskCache(Logger logger, Path file) {
        this.logger = logger;
        this.file = file;
        load();
    }

    public Entry find(String pngHash) {
        return entries.get(pngHash);
    }

    public boolean contains(String pngHash) {
        return entries.containsKey(pngHash);
    }

    public int size() { return entries.size(); }

    /** Hash convenience: SHA-256 of PNG bytes. */
    public static String hashPng(byte[] pngBytes) {
        return Hashing.sha256OfBytes(pngBytes);
    }

    /**
     * Add an entry and persist immediately. Persists synchronously on the
     * caller's thread — typical bake throughput is &lt; 1 entry/sec which is
     * well within disk write headroom for a kilobyte-scale JSON file.
     */
    public void put(String pngHash, String value, String signature, String uuid) {
        entries.put(pngHash, new Entry(value, signature, uuid, System.currentTimeMillis() / 1000L));
        save();
    }

    /** Remove all entries. Used by debug / manual reset. */
    public void clear() {
        entries.clear();
        save();
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("skins")) return;
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("skins").entrySet()) {
                JsonObject sk = e.getValue().getAsJsonObject();
                entries.put(e.getKey(), new Entry(
                        sk.get("value").getAsString(),
                        sk.get("signature").getAsString(),
                        sk.has("uuid") ? sk.get("uuid").getAsString() : null,
                        sk.has("addedAt") ? sk.get("addedAt").getAsLong() : 0L));
            }
            logger.info("[skin-cache] loaded " + entries.size() + " entries from " + file);
        } catch (IOException io) {
            logger.warning("[skin-cache] failed to read " + file + ": " + io.getMessage());
        } catch (RuntimeException re) {
            logger.log(Level.WARNING, "[skin-cache] malformed " + file, re);
        }
    }

    private synchronized void save() {
        JsonObject root = new JsonObject();
        JsonObject skins = new JsonObject();
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            JsonObject sk = new JsonObject();
            sk.addProperty("value", e.getValue().value());
            sk.addProperty("signature", e.getValue().signature());
            if (e.getValue().uuid() != null) sk.addProperty("uuid", e.getValue().uuid());
            sk.addProperty("addedAt", e.getValue().addedAt());
            skins.add(e.getKey(), sk);
        }
        root.add("skins", skins);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException io) {
            logger.log(Level.WARNING, "[skin-cache] failed to write " + file, io);
        }
    }
}
