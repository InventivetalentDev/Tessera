package org.inventivetalent.tessera.skin;

import org.inventivetalent.tessera.plugin.Bbb;
import org.mineskin.ClientBuilder;
import org.mineskin.JsoupRequestHandler;
import org.mineskin.MineSkinClient;
import org.mineskin.data.SkinInfo;
import org.mineskin.data.Visibility;
import org.mineskin.exception.MineSkinRequestException;
import org.mineskin.options.GenerateQueueOptions;
import org.mineskin.request.GenerateRequest;
import org.mineskin.response.QueueResponse;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Drives MineSkin uploads for {@link HeadSkin}s. Adapted from Mosaikin's
 * {@code SkinFactory}.
 *
 * <p>Uploads are submitted in batches of {@link #BATCH_SIZE}: we fan out one
 * batch, await every future in it, then start the next. The MineSkin client
 * still handles per-request rate-limiting inside a batch — batching exists
 * so a {@link Run#cancel()} call can reliably interrupt large bake / upload
 * sessions (only the current batch has been accepted by the server;
 * everything beyond never leaves the JVM).
 *
 * <p>State machine on each {@link HeadSkin}:
 * {@code PENDING → SUBMITTED (jobId) → COMPLETED} on success;
 * {@code PENDING → SUBMITTED → ERRORED} on rejection.
 * On plugin restart, callers can reset {@code SUBMITTED → PENDING} rather
 * than chasing job IDs across the restart — MineSkin's queue handles
 * duplicate uploads gracefully.
 *
 * <p>This class is reusable from both the runtime plugin and the bake task
 * (where there's no Bukkit). It depends only on {@link Logger} and the
 * MineSkin client.
 */
public final class SkinUploader {

    private static final int BATCH_SIZE = 32;

    /**
     * Paid-mode (BBB) configuration. When non-null,
     * {@link #rebuildClient} points the client at our backend and attaches
     * a jsoup interceptor that adds the license/identity headers our
     * backend expects on every request. The api-key argument is ignored
     * in paid mode — the backend uses its own server-side MineSkin key
     * and treats the {@code X-Tessera-License} header as auth.
     */
    public record PaidContext(
            String licenseKey,
            String nonce,
            String bbbUserId,
            String bbbResourceId,
            String pluginVersion,
            String serverId
    ) {}

    private final Logger logger;
    private final String userAgent;
    private final PaidContext paidContext;
    private MineSkinClient client;

    /** Active runs, keyed by run ID, so {@link #cancelAll()} can reach them. */
    private final Map<String, Run> runs = new ConcurrentHashMap<>();

    public SkinUploader(Logger logger, String userAgent, String apiKey) {
        this(logger, userAgent, apiKey, null);
    }

    public SkinUploader(Logger logger, String userAgent, String apiKey, PaidContext paidContext) {
        this.logger = logger;
        this.userAgent = userAgent;
        this.paidContext = paidContext;
        rebuildClient(apiKey);
    }

    public boolean isReady() {
        return client != null;
    }

    public boolean isPaidMode() {
        return paidContext != null;
    }

    private void rebuildClient(String apiKey) {
        try {
            ClientBuilder builder = MineSkinClient.builder()
                    .userAgent(userAgent)
                    .generateQueueOptions(GenerateQueueOptions.createAuto());
            if (paidContext != null) {
                final PaidContext ctx = paidContext;
                builder
                        .baseUrl(Bbb.BACKEND_BASE_URL)
                        .requestHandler(JsoupRequestHandler.withRequestInterceptor(conn -> {
                            conn.header("X-Tessera-License", ctx.licenseKey());
                            conn.header("X-Tessera-Nonce", ctx.nonce());
                            conn.header("X-Tessera-BBB-User", ctx.bbbUserId());
                            conn.header("X-Tessera-BBB-Resource", ctx.bbbResourceId());
                            conn.header("X-Tessera-Plugin-Version", ctx.pluginVersion());
                            conn.header("X-Tessera-Server-Id", ctx.serverId());
                        }));
                // No apiKey() call — backend auth is the license header.
            } else {
                builder.requestHandler(JsoupRequestHandler::new);
                if (apiKey != null && !apiKey.isBlank()) {
                    builder.apiKey(apiKey);
                }
            }
            this.client = builder.build();
        } catch (Throwable t) {
            logger.log(Level.SEVERE,
                    "Failed to initialize MineSkin client - uploads disabled", t);
            this.client = null;
        }
    }

    /**
     * Upload all heads. Returns a {@link Run} so the caller can cancel or
     * await completion. Each successful upload triggers {@code onComplete}
     * synchronously on the upload thread — the caller is responsible for
     * scheduling Bukkit work on the main thread if needed.
     */
    public Run upload(List<HeadSkin> heads, Path pngBaseDir, Consumer<HeadSkin> onComplete) {
        Run run = new Run();
        runs.put(run.id, run);

        if (client == null) {
            String reason = isPaidMode()
                    ? "MineSkin client not initialized - tessera-backend unreachable at startup"
                    : "MineSkin client not initialized - set mineskinApiKey in config.yml";
            run.future.completeExceptionally(new IllegalStateException(reason));
            runs.remove(run.id);
            return run;
        }

        List<HeadSkin> pending = new ArrayList<>();
        for (HeadSkin h : heads) {
            if (h.state() == SkinState.PENDING || h.state() == SkinState.ERRORED) {
                pending.add(h);
            } else if (h.state() == SkinState.SUBMITTED) {
                // Restart-recovery: SUBMITTED across a restart means we lost
                // the awaiter. Resubmit; MineSkin de-dupes server-side.
                h.state(SkinState.PENDING);
                h.jobId(null);
                pending.add(h);
            }
        }

        run.future = pending.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : runBatch(run, pending, 0, pngBaseDir, onComplete);

        run.future.whenComplete((v, ex) -> {
            runs.remove(run.id);
            if (ex != null && !run.cancelled.get()) {
                logger.log(Level.WARNING, "Upload run " + run.id + " failed", ex);
            }
        });

        return run;
    }

    private CompletableFuture<Void> runBatch(Run run, List<HeadSkin> pending, int offset,
                                             Path pngBaseDir, Consumer<HeadSkin> onComplete) {
        if (run.cancelled.get() || offset >= pending.size()) {
            return CompletableFuture.completedFuture(null);
        }
        int end = Math.min(offset + BATCH_SIZE, pending.size());
        List<CompletableFuture<Void>> batch = new ArrayList<>(end - offset);
        for (int i = offset; i < end; i++) {
            if (run.cancelled.get()) break;
            CompletableFuture<Void> f = uploadOne(run, pending.get(i), pngBaseDir, onComplete);
            run.futures.add(f);
            batch.add(f);
        }
        if (batch.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(batch.toArray(new CompletableFuture[0]))
                .thenCompose(v -> runBatch(run, pending, end, pngBaseDir, onComplete));
    }

    private CompletableFuture<Void> uploadOne(Run run, HeadSkin head, Path pngBaseDir,
                                              Consumer<HeadSkin> onComplete) {
        if (run.cancelled.get()) return CompletableFuture.completedFuture(null);
        Path png = head.pngFile() == null ? null : pngBaseDir.resolve(head.pngFile());
        if (png == null || !png.toFile().isFile()) {
            logger.warning("Skipping head " + head.id() + " - PNG missing at " + png);
            head.state(SkinState.ERRORED);
            return CompletableFuture.completedFuture(null);
        }
        File pngFile = png.toFile();
        GenerateRequest request = GenerateRequest.upload(pngFile)
                .name("tessera")
                .visibility(Visibility.UNLISTED);

        return client.queue().submit(request)
                .thenCompose(qr -> {
                    if (run.cancelled.get()) {
                        return CompletableFuture.failedFuture(
                                new CancellationException("cancelled"));
                    }
                    return onSubmitted(head, qr);
                })
                .thenAccept(skin -> {
                    if (run.cancelled.get()) return;
                    applySkin(head, skin);
                    if (onComplete != null) onComplete.accept(head);
                })
                .exceptionally(ex -> {
                    if (isCancellation(ex) || run.cancelled.get()) return null;
                    onFailure(head, ex);
                    return null;
                });
    }

    private CompletableFuture<SkinInfo> onSubmitted(HeadSkin head, QueueResponse qr) {
        try {
            head.jobId(qr.getJob().id());
            head.state(SkinState.SUBMITTED);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Failed to record job id for head " + head.id(), t);
        }
        return qr.getJob().waitForCompletion(client)
                .thenCompose(ref -> ref.getOrLoadSkin(client));
    }

    private void applySkin(HeadSkin head, SkinInfo skin) {
        try {
            head.texture(
                    skin.texture().data().value(),
                    skin.texture().data().signature(),
                    skin.uuid());
            head.state(SkinState.COMPLETED);
        } catch (Throwable t) {
            logger.log(Level.WARNING,
                    "Failed to extract texture data for head " + head.id(), t);
            head.state(SkinState.ERRORED);
        }
    }

    private void onFailure(HeadSkin head, Throwable ex) {
        if (ex instanceof MineSkinRequestException mre) {
            logger.log(Level.WARNING,
                    "MineSkin rejected head " + head.id() + ": " + mre.getMessage());
        } else {
            logger.log(Level.WARNING, "Head " + head.id() + " upload failed", ex);
        }
        head.state(SkinState.ERRORED);
    }

    private static boolean isCancellation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof CancellationException) return true;
        }
        return false;
    }

    public void cancelAll() {
        for (Run r : runs.values()) r.cancel();
    }

    public static final class Run {
        private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

        public final String id = "run-" + SEQ.incrementAndGet();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final List<CompletableFuture<Void>> futures =
                Collections.synchronizedList(new ArrayList<>());
        private CompletableFuture<Void> future = CompletableFuture.completedFuture(null);

        public CompletableFuture<Void> future() { return future; }

        public void cancel() {
            cancelled.set(true);
            synchronized (futures) {
                for (CompletableFuture<Void> f : futures) f.cancel(true);
            }
        }
    }
}
