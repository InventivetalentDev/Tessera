package io.tessera.assemble;

import io.tessera.core.ChunkCoord;
import io.tessera.core.FaceDir;
import io.tessera.core.HeadFace;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Spawns a {@link BlockDisplay}-based test lattice at a target location for
 * empirical tuning of {@link BlockGeometry#cubeCenterPre} and
 * {@link FaceRotations}. Each cell of the {@code gridN³} lattice is rendered
 * as a tiny vanilla block with the same Transformation we'd use for a real
 * chunk display — same translation math, same scale, same right-rotation —
 * but without needing any MineSkin/heads.json data.
 *
 * <p>If the lattice's cubes meet flush with no staircase gaps, the
 * geometry is correctly tuned. If they don't, run
 * {@code /tessera debug center <x> <y> <z>} and re-spawn until they line up;
 * fold the working values into {@link BlockGeometry#CUBE_CENTER_PRE_DEFAULT}.
 *
 * <p>Cubes auto-despawn after {@link #LIFETIME_TICKS} so a typo doesn't
 * leave clutter behind.
 */
public final class DebugGridSpawner {

    private static final int LIFETIME_TICKS = 30 * 20; // 30 seconds

    private DebugGridSpawner() {}

    /**
     * Spawn a lattice at {@code anchor} with the same per-cell math as
     * {@link FakeBlockFactory}. Every cell uses {@code material} as its
     * block; pick something with bright distinguishable face textures
     * (default {@link Material#DIAMOND_ORE} reads well against most
     * environments).
     */
    public static List<BlockDisplay> spawn(Plugin plugin, Location anchor, int gridN, Material material) {
        World world = anchor.getWorld();
        if (world == null) throw new IllegalArgumentException("Anchor has no world");

        BlockGeometry geom = BlockGeometry.axisAligned(gridN);
        Location origin = new Location(world,
                Math.floor(anchor.getX()),
                Math.floor(anchor.getY()),
                Math.floor(anchor.getZ()));

        List<BlockDisplay> spawned = new ArrayList<>();
        for (int x = 0; x < gridN; x++) {
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    boolean visible = isVisible(x, y, z, gridN);
                    if (!visible) continue;

                    ChunkCoord c = new ChunkCoord(x, y, z);
                    FaceDir primary = primaryFaceFor(c, gridN);
                    HeadFace headFace = mapFaceDir(primary);
                    Quaternionf faceRot = FaceRotations.of(headFace);

                    Vector3f translation = geom.translationFor(c, faceRot);
                    float scale = geom.chunkScale();

                    Transformation tx = new Transformation(
                            translation,
                            new Quaternionf(),
                            new Vector3f(scale, scale, scale),
                            faceRot);

                    BlockDisplay display = world.spawn(origin, BlockDisplay.class, d -> {
                        d.setBlock(material.createBlockData());
                        d.setTransformation(tx);
                        d.setBrightness(new Display.Brightness(15, 15));
                        d.setViewRange(2.0f);
                        d.setPersistent(false);
                    });
                    spawned.add(display);
                }
            }
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (BlockDisplay d : spawned) if (!d.isDead()) d.remove();
        }, LIFETIME_TICKS);

        return spawned;
    }

    /**
     * For visualizing every cell (including interior), call with the same
     * gridN — uses the same math as {@link FakeBlockFactory} but spawns
     * interior cells too so you can see whether translations are correct
     * across the whole volume.
     */
    public static List<ItemDisplay> spawnDense(Plugin plugin, Location anchor, int gridN, ItemStack item) {
        World world = anchor.getWorld();
        if (world == null) throw new IllegalArgumentException("Anchor has no world");
        BlockGeometry geom = BlockGeometry.axisAligned(gridN);
        Location origin = new Location(world,
                Math.floor(anchor.getX()),
                Math.floor(anchor.getY()),
                Math.floor(anchor.getZ()));

        List<ItemDisplay> spawned = new ArrayList<>();
        for (int x = 0; x < gridN; x++) {
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    ChunkCoord c = new ChunkCoord(x, y, z);
                    FaceDir primary = isVisible(x, y, z, gridN) ? primaryFaceFor(c, gridN) : FaceDir.UP;
                    HeadFace headFace = mapFaceDir(primary);
                    Quaternionf faceRot = FaceRotations.of(headFace);
                    Vector3f translation = geom.translationFor(c, faceRot);
                    float scale = geom.chunkScale();
                    Transformation tx = new Transformation(translation, new Quaternionf(),
                            new Vector3f(scale, scale, scale), faceRot);

                    ItemDisplay display = world.spawn(origin, ItemDisplay.class, d -> {
                        d.setItemStack(item);
                        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                        d.setTransformation(tx);
                        d.setBrightness(new Display.Brightness(15, 15));
                        d.setViewRange(2.0f);
                        d.setPersistent(false);
                    });
                    spawned.add(display);
                }
            }
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (ItemDisplay d : spawned) if (!d.isDead()) d.remove();
        }, LIFETIME_TICKS);
        return spawned;
    }

    private static boolean isVisible(int x, int y, int z, int gridN) {
        for (FaceDir d : FaceDir.values()) {
            if (d.isOutwardAt(x, y, z, gridN)) return true;
        }
        return false;
    }

    private static FaceDir primaryFaceFor(ChunkCoord c, int gridN) {
        for (FaceDir d : FaceDir.values()) {
            if (d.isOutwardAt(c.x(), c.y(), c.z(), gridN)) return d;
        }
        return FaceDir.UP;
    }

    private static HeadFace mapFaceDir(FaceDir d) {
        return switch (d) {
            case UP    -> HeadFace.TOP;
            case DOWN  -> HeadFace.BOTTOM;
            case SOUTH -> HeadFace.FRONT;
            case NORTH -> HeadFace.BACK;
            case EAST  -> HeadFace.RIGHT;
            case WEST  -> HeadFace.LEFT;
        };
    }
}
