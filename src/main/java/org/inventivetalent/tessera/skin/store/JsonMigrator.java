package org.inventivetalent.tessera.skin.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.inventivetalent.tessera.assets.model.ModelResolver.VariantRotation;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.ChunkCoord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One-shot converter from the legacy {@code heads-{N}.json} layout to the
 * new {@code .tsra} folder / {@code .ztsra} zip. Kept around for two paths:
 * <ul>
 *   <li>Runtime cache migration — when an installed server upgrades to a
 *       version of Tessera that uses {@code .tsra}, the plugin spots a
 *       leftover {@code plugins/Tessera/cache/heads-{N}.json}, migrates
 *       it into the folder store on first boot, and renames the JSON to
 *       {@code .migrated} so the next start skips the conversion.</li>
 *   <li>Build-time bundling — the converter that turns the committed
 *       {@code src/main/resources/heads-{N}.json} into the shipped
 *       {@code heads-{N}.ztsra} resource. The same code is also exposed
 *       via {@link #zipFolder} for tests.</li>
 * </ul>
 */
public final class JsonMigrator {

    private JsonMigrator() {}

    /**
     * Migrate {@code jsonFile} (in the legacy heads-{N}.json schema) into
     * {@code target} (a writable {@link TsraFolderStore} root). Existing
     * entries in {@code target} are not touched — the migrator only adds.
     * Returns the number of blocks migrated. Block keys are parsed via
     * {@link BakeKey#parse(String)} so both bundled (untinted-only) and
     * runtime ({@code "...#7fbf2eff"}-suffixed) JSON files round-trip.
     */
    public static int migrate(Logger logger, Path jsonFile, TsraFolderStore target,
                              int defaultGridN, String defaultVersion) {
        if (!Files.isRegularFile(jsonFile)) return 0;
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(jsonFile, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException io) {
            logger.log(Level.WARNING, "[tsra-migrate] failed to read " + jsonFile, io);
            return 0;
        } catch (RuntimeException re) {
            logger.log(Level.WARNING, "[tsra-migrate] malformed " + jsonFile, re);
            return 0;
        }
        int gridN = root.has("gridN") ? root.get("gridN").getAsInt() : defaultGridN;
        String version = root.has("version") ? root.get("version").getAsString() : defaultVersion;

        target.writeManifest(new TsraFormat.Manifest(gridN, version, "tessera-json-migrator"));

        // Skin table: hash → {value, signature, mineskinUuid?}
        Map<String, TsraFormat.Skin> skinByHash = new LinkedHashMap<>();
        if (root.has("skins")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("skins").entrySet()) {
                JsonObject sk = e.getValue().getAsJsonObject();
                String hash = e.getKey();
                String value = sk.has("value") ? sk.get("value").getAsString() : "";
                String sig = sk.has("signature") ? sk.get("signature").getAsString() : "";
                String uuid = sk.has("mineskinUuid") && !sk.get("mineskinUuid").isJsonNull()
                        ? sk.get("mineskinUuid").getAsString() : null;
                skinByHash.put(hash, new TsraFormat.Skin(hash, value, sig, uuid));
            }
        }
        for (TsraFormat.Skin s : skinByHash.values()) target.writeSkin(s);

        int migrated = 0;
        if (root.has("blocks")) {
            for (Map.Entry<String, JsonElement> be : root.getAsJsonObject("blocks").entrySet()) {
                BakeKey key;
                try { key = BakeKey.parse(be.getKey()); }
                catch (RuntimeException re) {
                    logger.warning("[tsra-migrate] skipping malformed block key " + be.getKey());
                    continue;
                }
                JsonObject obj = be.getValue().getAsJsonObject();

                Map<ChunkCoord, String> chunkHashes = new LinkedHashMap<>();
                if (obj.has("chunks")) {
                    for (Map.Entry<String, JsonElement> ce : obj.getAsJsonObject("chunks").entrySet()) {
                        ChunkCoord coord;
                        try { coord = ChunkCoord.parseKey(ce.getKey()); }
                        catch (RuntimeException ignored) { continue; }
                        if (!ce.getValue().isJsonObject()) continue;
                        JsonElement hashEl = ce.getValue().getAsJsonObject().get("skinHash");
                        if (hashEl == null || hashEl.isJsonNull() || !hashEl.isJsonPrimitive()
                                || !hashEl.getAsJsonPrimitive().isString()) continue;
                        chunkHashes.put(coord, hashEl.getAsString());
                    }
                }
                if (chunkHashes.isEmpty()) continue;

                Map<String, VariantRotation> variants = new LinkedHashMap<>();
                if (obj.has("variants")) {
                    for (Map.Entry<String, JsonElement> ve : obj.getAsJsonObject("variants").entrySet()) {
                        JsonObject vo = ve.getValue().getAsJsonObject();
                        int xDeg = vo.has("x") ? vo.get("x").getAsInt() : 0;
                        int yDeg = vo.has("y") ? vo.get("y").getAsInt() : 0;
                        variants.put(ve.getKey(), new VariantRotation(xDeg, yDeg));
                    }
                }

                target.writeBlock(new TsraFormat.Block(key, chunkHashes, variants));
                migrated++;
            }
        }
        logger.info("[tsra-migrate] migrated " + migrated + " block(s) and "
                + skinByHash.size() + " skin(s) from " + jsonFile);
        return migrated;
    }

    /**
     * Re-pack {@code folder} (a {@link TsraFolderStore} root) into a
     * {@code .ztsra} zip at {@code zipOut}. Used by the bake task to ship
     * the bundled resource: the bake writes to a folder under
     * {@code build/}, then the gradle task zips it into the resources tree.
     */
    public static void zipFolder(Path folder, Path zipOut) throws IOException {
        Files.createDirectories(zipOut.toAbsolutePath().getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipOut))) {
            // No compression on already-tightly-packed bake-time bytes — the
            // skin value/signature blobs are base64-encoded random data so
            // deflate buys ~5% at the cost of CPU on every read.
            zos.setLevel(Deflater.NO_COMPRESSION);
            try (var walk = Files.walk(folder)) {
                walk.filter(Files::isRegularFile).sorted().forEach(p -> {
                    String name = folder.relativize(p).toString().replace('\\', '/');
                    try {
                        ZipEntry e = new ZipEntry(name);
                        zos.putNextEntry(e);
                        zos.write(Files.readAllBytes(p));
                        zos.closeEntry();
                    } catch (IOException io) {
                        throw new RuntimeException("zip " + name + " failed", io);
                    }
                });
            }
        }
    }
}
