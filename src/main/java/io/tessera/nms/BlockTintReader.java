package io.tessera.nms;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LevelAccessor;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlock;

/**
 * Reads the resolved client-side biome tint for a block via NMS. Mirrors the
 * value vanilla Minecraft would multiply into the texture when rendering —
 * we apply the same multiplier to the source PNGs at bake time so the
 * FakeBlock matches its real-block neighbours.
 *
 * <p><b>Main thread only.</b> {@code LevelAccessor.getBlockTint} reads chunk
 * biome storage, which races with chunk unloads off-thread. Callers must
 * invoke this from the main server thread (e.g. inside a Bukkit event
 * handler), capture the returned int, and only then hop off to async work.
 *
 * <p>Returns {@code 0} (the {@link io.tessera.core.BakeKey} sentinel for
 * "untinted") for: any block type we don't have a resolver for, any block
 * that isn't a {@link CraftBlock}, and any unexpected NMS linkage error
 * (defensive — a Paper bump should not brick block breaking).
 */
public final class BlockTintReader {

    private BlockTintReader() {}

    // ColorResolver lambdas mirror what net.minecraft.client.renderer.BiomeColors
    // does on the client; that class isn't on the server classpath so we
    // inline the equivalent server-side calls.
    private static final ColorResolver GRASS = (biome, x, z) -> biome.getGrassColor(x, z);
    private static final ColorResolver FOLIAGE = (biome, x, z) -> biome.getFoliageColor();
    private static final ColorResolver WATER = (biome, x, z) -> biome.getWaterColor();

    public static int read(Block block) {
        if (!(block instanceof CraftBlock craftBlock)) return 0;
        ColorResolver resolver = pickResolver(block.getType().getKey().getKey());
        if (resolver == null) return 0;
        try {
            LevelAccessor level = craftBlock.getHandle();
            BlockPos pos = craftBlock.getPosition();
            int rgb = level.getBlockTint(pos, resolver);
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
}
