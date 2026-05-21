package org.inventivetalent.tessera.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import org.inventivetalent.tessera.skin.SkinUploader.PaidContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * HTTP client for the Tessera backend (licensed-mode only). Hits the archive
 * endpoints with the same {@code X-Tessera-*} headers our MineSkin
 * interceptor attaches so the backend can gate and audit by license.
 *
 * <p>The download path follows the backend's 302 redirect to a presigned
 * R2 URL <em>manually</em> rather than letting {@link HttpClient}'s
 * normal redirect handling do it — that way we don't leak license headers
 * to R2 (which doesn't want them and treats them as signature input on
 * some endpoints). Stage: GET {@code /archives/{id}/download} with our
 * headers → read {@code Location} → fresh GET against R2 with no auth.
 */
public final class BackendClient {

    private final Logger logger;
    private final String userAgent;
    private final PaidContext ctx;
    private final HttpClient http;
    private final Gson gson = new Gson();

    public BackendClient(Logger logger, String userAgent, PaidContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("BackendClient requires paid context");
        this.logger = logger;
        this.userAgent = userAgent;
        this.ctx = ctx;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Public archive metadata returned by {@code GET /archives}. */
    public record ArchiveSummary(
            int id,
            String name,
            @SerializedName("gridN") int gridN,
            @SerializedName("mcVersion") String mcVersion,
            String description,
            String sha256,
            long size,
            @SerializedName("uploadedAt") long uploadedAt
    ) {}

    private record ArchivesResponse(List<ArchiveSummary> archives) {}

    public List<ArchiveSummary> listArchives(Integer gridN) throws IOException {
        String url = License.BACKEND_BASE_URL + "/archives"
                + (gridN != null ? "?gridN=" + URLEncoder.encode(gridN.toString(), StandardCharsets.UTF_8) : "");
        HttpRequest req = baseRequest(url).GET().build();
        HttpResponse<String> resp = send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + url + ": " + truncate(resp.body()));
        }
        try {
            ArchivesResponse r = gson.fromJson(resp.body(), ArchivesResponse.class);
            return r == null || r.archives() == null ? Collections.emptyList() : r.archives();
        } catch (JsonSyntaxException js) {
            throw new IOException("Malformed JSON from " + url + ": " + truncate(resp.body()), js);
        }
    }

    /**
     * Stream the archive identified by {@code id} into {@code destDir}.
     * Returns the final file path. Existing files at the destination are
     * overwritten atomically — if the move fails, the partial download is
     * cleaned up.
     */
    public Path downloadArchive(int id, String displayName, Path destDir) throws IOException {
        String stage1 = License.BACKEND_BASE_URL + "/archives/" + id + "/download";
        HttpRequest stage1Req = baseRequest(stage1).GET().build();
        HttpResponse<Void> stage1Resp = send(stage1Req, HttpResponse.BodyHandlers.discarding());
        int code = stage1Resp.statusCode();
        if (code != 302 && code != 301 && code != 307 && code != 308) {
            throw new IOException("Expected redirect from " + stage1 + ", got HTTP " + code);
        }
        String location = stage1Resp.headers().firstValue("location").orElse(null);
        if (location == null || location.isBlank()) {
            throw new IOException("Backend redirect missing Location header for archive " + id);
        }

        // Stage 2: hit R2 with no license headers — presigned URL provides its own auth.
        HttpRequest stage2Req = HttpRequest.newBuilder(URI.create(location))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        Files.createDirectories(destDir);
        Path tmp = Files.createTempFile(destDir, ".download-", ".part");
        try {
            HttpResponse<InputStream> stage2Resp = send(stage2Req, HttpResponse.BodyHandlers.ofInputStream());
            if (stage2Resp.statusCode() / 100 != 2) {
                throw new IOException("R2 returned HTTP " + stage2Resp.statusCode() + " for archive " + id);
            }
            try (InputStream in = stage2Resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Path target = destDir.resolve(sanitize(displayName) + ".ztsra");
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // best-effort cleanup
            }
            throw e;
        }
    }

    private HttpRequest.Builder baseRequest(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("X-Tessera-License", ctx.licenseKey())
                .header("X-Tessera-Plugin-Version", ctx.pluginVersion())
                .header("X-Tessera-Server-Id", ctx.serverId());
        if (ctx.distributorUserId() != null) {
            b.header("X-Tessera-Distributor-User", ctx.distributorUserId());
        }
        if (ctx.distributorResourceId() != null) {
            b.header("X-Tessera-Distributor-Resource", ctx.distributorResourceId());
        }
        return b;
    }

    private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(req, handler);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted calling " + req.uri(), ie);
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 256 ? s.substring(0, 256) + "..." : s;
    }

    @SuppressWarnings("unused")
    private Logger logger() { return logger; }
}
