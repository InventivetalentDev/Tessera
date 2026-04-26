package io.tessera.assets.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the mcasset.cloud raw-asset host with a per-version
 * on-disk cache.
 *
 * <p>Note the {@code assets.} subdomain — {@code mcasset.cloud} alone returns
 * the browser UI page, not the file. URL pattern:
 * {@code https://assets.mcasset.cloud/<version>/assets/minecraft/<path>}.
 *
 * <p>Cached files live under {@code <cacheRoot>/<version>/<path>}. ETag
 * handling is unnecessary because the version is pinned — once a file is
 * cached for {@code 1.21.4}, it stays valid forever.
 */
public final class McAssetClient {

    private static final String HOST = "https://assets.mcasset.cloud";

    private final HttpClient http;
    private final Path cacheRoot;
    private final Logger logger;

    public McAssetClient(Path cacheRoot, Logger logger) {
        this.cacheRoot = cacheRoot;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Fetch the raw bytes for an asset path, caching to disk on first hit.
     * {@code assetPath} is everything after {@code assets/minecraft/} —
     * e.g. {@code blockstates/stone.json} or {@code textures/block/dirt.png}.
     */
    public byte[] fetch(String version, String assetPath) throws IOException {
        Path cached = cachePath(version, assetPath);
        if (Files.isRegularFile(cached)) {
            return Files.readAllBytes(cached);
        }

        String url = HOST + "/" + version + "/assets/minecraft/" + assetPath;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Tessera/0.1 (mcasset fetch)")
                .GET()
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted fetching " + url, e);
        }
        if (resp.statusCode() == 404) {
            throw new AssetNotFoundException(url);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        Files.createDirectories(cached.getParent());
        Files.write(cached, resp.body());
        return resp.body();
    }

    /** UTF-8 string convenience over {@link #fetch}. */
    public String fetchString(String version, String assetPath) throws IOException {
        return new String(fetch(version, assetPath), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Resolve the absolute cache path for an asset, whether or not it has been fetched. */
    public Path cachePath(String version, String assetPath) {
        return cacheRoot.resolve(version).resolve(assetPath);
    }

    /** Thrown when mcasset.cloud returns 404 — caller can decide whether that's expected (e.g. variant model missing). */
    public static final class AssetNotFoundException extends IOException {
        public AssetNotFoundException(String url) {
            super("404 Not Found: " + url);
        }
    }
}
