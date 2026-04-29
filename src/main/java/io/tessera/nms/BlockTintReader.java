package io.tessera.nms;

import io.tessera.assets.fetch.McAssetClient;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.LevelAccessor;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlock;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
 * <p><b>Main thread only.</b> {@code LevelAccessor.getBlockTint} reads chunk
 * biome storage, which races with chunk unloads off-thread. Callers must
 * invoke {@link #read} from the main server thread, capture the returned
 * int, and only then hop off to async work.
 *
 * <p>Returns {@code 0} (the {@link io.tessera.core.BakeKey} sentinel for
 * "untinted") for: any block type we don't have a resolver for, any block
 * that isn't a {@link CraftBlock}, and any unexpected NMS linkage error
 * (defensive — a Paper bump should not brick block breaking).
 */
public final class BlockTintReader {

    private BlockTintReader() {}

    private static volatile boolean colormapsLoaded;

    // ColorResolver lambdas mirror what net.minecraft.client.renderer.BiomeColors
    // does on the client; that class isn't on the server classpath so we
    // inline the equivalent server-side calls.
    private static final ColorResolver GRASS = (biome, x, z) -> biome.getGrassColor(x, z);
    private static final ColorResolver FOLIAGE = (biome, x, z) -> biome.getFoliageColor();
    private static final ColorResolver WATER = (biome, x, z) -> biome.getWaterColor();

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
        if (!(block instanceof CraftBlock craftBlock)) return 0;
        ColorResolver resolver = pickResolver(block.getType().getKey().getKey());
        if (resolver == null) return 0;
        try {
            LevelAccessor level = craftBlock.getHandle();
            BlockPos pos = craftBlock.getPosition();
            int rgb = level.getBlockTint(pos, resolver);
            // Treat 0 RGB as "no tint available" (e.g. colormap not loaded
            // and biome has no override) and return the untinted sentinel
            // so we don't bake a useless all-black variant.
            if ((rgb & 0xFFFFFF) == 0) return 0;
            // Force opaque alpha so the int is stable as a BakeKey identity:
            // some resolvers may return 0xRRGGBB (alpha zero) and some
            // 0xFFRRGGBB depending on sample path; either way the multiply
            // we do downstream wants the alpha bit set.
            return 0xFF000000 | (rgb & 0xFFFFFF);
        } catch (LinkageError | RuntimeException e) {
            return 0;
        }
    }

    private static ColorResolver pickResolver(String path) {
        return switch (path) {
            case "grass_block", "short_grass", "tall_grass", "fern", "large_fern",
                 "sugar_cane", "potted_fern" -> GRASS;
            case "oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves",
                 "mangrove_leaves", "azalea_leaves", "flowering_azalea_leaves", "vine" -> FOLIAGE;
            case "water", "bubble_column" -> WATER;
            // Out of v1 scope:
            //   spruce_leaves / birch_leaves / cherry_leaves — constant tints,
            //     not biome-driven; trivial follow-up.
            //   redstone_wire — power-level dependent, needs power in BakeKey.
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
