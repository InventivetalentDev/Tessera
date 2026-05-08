package org.inventivetalent.tessera.skin;

import org.inventivetalent.tessera.util.Hashing;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

/**
 * Manages a single cached placeholder player-head skin for use when a block
 * hasn't been baked yet. Generates a 64×64 solid-color PNG from a configured
 * hex color, uploads it to MineSkin once, and caches the result in the
 * existing {@link SkinDiskCache} so it survives restarts.
 *
 * <p>{@link #update(String)} is safe to call from the main thread — the
 * upload runs on the baker executor and sets {@link #currentHead()} when done.
 */
public final class PlaceholderSkinManager {

    private final Logger logger;
    private final SkinUploader uploader;
    private final SkinDiskCache diskCache;
    private final Path pngDir;
    private final Executor executor;

    private volatile HeadSkin currentHead;
    private volatile String lastColor;

    public PlaceholderSkinManager(Logger logger, SkinUploader uploader,
                                  SkinDiskCache diskCache, Path pngDir, Executor executor) {
        this.logger = logger;
        this.uploader = uploader;
        this.diskCache = diskCache;
        this.pngDir = pngDir;
        this.executor = executor;
    }

    /**
     * Schedule an async upload for the given hex color if it differs from the
     * last requested color. Safe to call repeatedly — no-ops when the color is
     * unchanged. {@link #currentHead()} is updated once the upload completes.
     */
    public void update(String hexColor) {
        if (hexColor == null || hexColor.equals(lastColor)) return;
        lastColor = hexColor;
        currentHead = null;
        CompletableFuture.runAsync(() -> doUpdate(hexColor), executor)
                .exceptionally(ex -> {
                    logger.warning("[placeholder] failed: " + ex.getMessage());
                    return null;
                });
    }

    /** Returns the ready-to-use placeholder skin, or {@code null} if not yet uploaded. */
    public HeadSkin currentHead() {
        return currentHead;
    }

    private void doUpdate(String hexColor) {
        int argb = parseArgb(hexColor);

        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(argb, true));
        g.fillRect(0, 0, 64, 64);
        g.dispose();

        byte[] pngBytes;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
            ImageIO.write(img, "PNG", baos);
            pngBytes = baos.toByteArray();
        } catch (IOException e) {
            logger.warning("[placeholder] failed to encode PNG: " + e.getMessage());
            return;
        }

        String hash = Hashing.sha256OfBytes(pngBytes);

        SkinDiskCache.Entry cached = diskCache.find(hash);
        if (cached != null) {
            HeadSkin head = new HeadSkin(UUID.randomUUID(), hash, Collections.emptyMap());
            head.texture(cached.value(), cached.signature(), cached.uuid());
            head.state(SkinState.COMPLETED);
            currentHead = head;
            logger.info("[placeholder] restored from cache for " + hexColor);
            return;
        }

        if (!uploader.isReady()) {
            logger.info("[placeholder] skipped — no MineSkin API key configured");
            return;
        }

        String fileName = "placeholder-" + hash.substring(0, 16) + ".png";
        Path pngFile = pngDir.resolve(fileName);
        try {
            Files.createDirectories(pngDir);
            Files.write(pngFile, pngBytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.warning("[placeholder] failed to write PNG: " + e.getMessage());
            return;
        }

        HeadSkin head = new HeadSkin(UUID.randomUUID(), hash, Collections.emptyMap());
        head.pngFile(fileName);
        head.state(SkinState.PENDING);

        SkinUploader.Run run = uploader.upload(List.of(head), pngDir, completed -> {
            if (completed.textureValue() != null && completed.textureSignature() != null) {
                diskCache.put(hash, completed.textureValue(), completed.textureSignature(),
                        completed.mineskinUuid());
                currentHead = completed;
                logger.info("[placeholder] uploaded for " + hexColor);
            }
        });

        try {
            run.future().get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            logger.warning("[placeholder] upload wait interrupted: " + e.getMessage());
        }
    }

    /** Parse a hex color string (#RRGGBB or #AARRGGBB) to an ARGB int. */
    static int parseArgb(String hex) {
        if (hex == null) return 0xFFFFFFFF;
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            if (s.length() == 6) return (int) (0xFF000000L | Long.parseLong(s, 16));
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException ignored) {}
        return 0xFFFFFFFF;
    }
}
