package io.tessera.nms;

import io.tessera.assets.fetch.McAssetClient;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the resolved client-side biome tint for a block via NMS. Mirrors the
 * value vanilla Minecraft would multiply into the texture when rendering —
 * we apply the same multiplier to the source PNGs at bake time so the
 * FakeBlock matches its real-block neighbours.
 *
 * <p><b>Server-side colormaps:</b> vanilla's {@code GrassColor.pixels} and
 * {@code FoliageColor.pixels} arrays are populated only by the client's
 * resource loader. On a dedicated server they're left as the default
 * all-zero array, so {@code Biome.getGrassColor(...)} returns 0 for every
 * biome that doesn't carry an explicit override (i.e. plains, forest,
 * mountains, ...). {@link #prepareColormaps} fetches the vanilla colormap
 * PNGs through {@link McAssetClient} and calls vanilla's
 * {@code GrassColor.init(int[])} / {@code FoliageColor.init(int[])} setters
 * so the existing biome-color API works server-side. Call once during
 * plugin enable, before any block-break events register.
 *
 * <p><b>Main thread only.</b> Biome lookup reads chunk biome storage, which
 * races with chunk unloads off-thread. Callers must invoke {@link #read}
 * from the main server thread, capture the returned int, and only then hop
 * off to async work.
 *
 * <p>Returns {@code 0} (the {@link io.tessera.core.BakeKey} sentinel for
 * "untinted") for: any block type we don't have a tint function for, and any
 * unexpected NMS linkage error (defensive — a Paper bump should not brick
 * block breaking).
 *
 * <p><b>Robustness note:</b> the old {@code LevelAccessor/LevelReader.getBlockTint(BlockPos, ColorResolver)}
 * API was removed in MC 26.1. We now call {@code Biome.getGrassColor(x, z)}
 * directly — a concrete method on the Biome class that's far less likely to
 * move between interface reshuffles. {@code LevelReader.getBiome(BlockPos)}
 * and {@code Holder.value()} are similarly stable.
 */
public final class BlockTintReader {

    private BlockTintReader() {}

    private static volatile boolean colormapsLoaded;
    private static final AtomicBoolean tintReadErrorLogged = new AtomicBoolean();

    /**
     * Fetch the vanilla grass + foliage colormap PNGs (256×256 each) and
     * inject them into vanilla's static pixel arrays so server-side
     * {@code Biome.getGrassColor(...)} / {@code getFoliageColor()} return
     * the correct per-biome tint.
     *
     * <p>Idempotent — subsequent calls are no-ops. Failures are logged but
     * not thrown; tinted blocks then keep returning 0 from {@link #read}
     * and skip baking, leaving vanilla particles as the user-visible
     * fallback.
     */
    public static synchronized void prepareColormaps(McAssetClient assets, String mcVersion, Logger log) {
        if (colormapsLoaded) return;
        try {
            GrassColor.init(loadColormap(assets.fetch(mcVersion, "textures/colormap/grass.png")));
            FoliageColor.init(loadColormap(assets.fetch(mcVersion, "textures/colormap/foliage.png")));
            colormapsLoaded = true;
            log.info("[Tessera] Biome colormaps loaded (grass + foliage)");
        } catch (IOException | LinkageError | RuntimeException e) {
            log.log(Level.WARNING,
                    "[Tessera] Could not load biome colormaps; tinted blocks will fall back to vanilla particles", e);
        }
    }

    public static int read(Block block) {
        TintFunction fn = pickTintFunction(block.getType().getKey().getKey());
        if (fn == null) return 0;
        try {
            LevelReader level = ((CraftWorld) block.getWorld()).getHandle();
            BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
            Holder<Biome> biomeHolder = level.getBiome(pos);
            int rgb = fn.apply(biomeHolder.value(), block.getX(), block.getZ());
            // Treat 0 RGB as "no tint available" (e.g. colormap not loaded
            // and biome has no explicit override) and return the untinted
            // sentinel so we don't bake a useless all-black variant.
            if ((rgb & 0xFFFFFF) == 0) return 0;
            // Force opaque alpha for a stable BakeKey identity — some
            // biome methods return 0xRRGGBB (alpha=0), others 0xFFRRGGBB.
            return 0xFF000000 | (rgb & 0xFFFFFF);
        } catch (LinkageError | RuntimeException e) {
            if (tintReadErrorLogged.compareAndSet(false, true)) {
                Logger.getLogger("Tessera").log(Level.WARNING,
                        "[Tessera] BlockTintReader.read failed (Paper API incompatibility?) — tinted blocks will skip baking", e);
            }
            return 0;
        }
    }

    @FunctionalInterface
    private interface TintFunction {
        int apply(Biome biome, double x, double z);
    }

    private static TintFunction pickTintFunction(String path) {
        return switch (path) {
            // Only solid-texture tinted blocks. Transparent-texture blocks
            // (leaves, water, vine) are excluded because player-head skins
            // don't support transparency — the semi-transparent leaf pixels
            // would render as holes in the ItemDisplay entity.
            // Non-cube variants (short_grass, fern, etc.) already fail the
            // full-cube element check in ModelResolver; listed here for
            // documentation.
            case "grass_block", "short_grass", "tall_grass", "fern", "large_fern",
                 "sugar_cane", "potted_fern" -> Biome::getGrassColor;
            default -> null;
        };
    }

    private static int[] loadColormap(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        if (img == null) throw new IOException("failed to decode colormap PNG");
        int w = img.getWidth();
        int h = img.getHeight();
        // GrassColor.get(t, h) indexes pixels[((1-h)*255)*256 + ((1-t)*255)],
        // i.e. row-major y*256+x. BufferedImage.getRGB writes the same layout.
        int[] out = new int[w * h];
        img.getRGB(0, 0, w, h, out, 0, w);
        return out;
    }
}
