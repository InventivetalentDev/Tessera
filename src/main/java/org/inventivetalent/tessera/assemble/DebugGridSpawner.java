package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.core.ChunkCoord;
import org.inventivetalent.tessera.core.FaceDir;
import org.inventivetalent.tessera.core.HeadFace;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
 * Spawns a lattice of {@link ItemDisplay} entities — one per visible chunk
 * cell — using the exact same transform math as {@link FakeBlockFactory}.
 * Each cell is a {@link Material#PLAYER_HEAD} with the default Steve skin
 * so the result is a visible preview of what a real baked FakeBlock would
 * look like, but without needing any MineSkin/heads.json data.
 *
 * <p>Empirical-tuning workflow:
 * <ol>
 *   <li>Stand on a flat area, look at empty space.</li>
 *   <li>{@code /tessera debug grid} — spawns the lattice 30s in front of you.</li>
 *   <li>If the cubes don't meet flush (staircase gaps along certain axes):
 *       run {@code /tessera debug center <x> <y> <z>} until they do, then
 *       fold the working values into {@link BlockGeometry#CUBE_CENTER_PRE_DEFAULT}.</li>
 *   <li>If a cube's face shows the wrong tile orientation: use
 *       {@code /tessera debug face <face> <x> <y> <z>} until correct, then
 *       fold into {@link FaceRotations} defaults.</li>
 * </ol>
 *
 * <p>An earlier version of this class spawned BlockDisplay entities, but
 * BlockDisplay has totally different rendering geometry (no internal
 * CUBE_CENTER_PRE offset, scale 1.0 == 1-block cube vs. player-head's
 * 0.5-block cube), so the result didn't actually exercise the FakeBlock
 * math. The current PlayerHead-based version exercises the real path.
 */
public final class DebugGridSpawner {

    private static final int LIFETIME_TICKS = 30 * 20; // 30 seconds

    private DebugGridSpawner() {}

    /**
     * Spawn a default-skin PlayerHead lattice at {@code anchor} using the
     * same per-chunk math as {@link FakeBlockFactory}. {@code material} is
     * accepted for future BlockDisplay-mode use but currently ignored
     * (always uses PLAYER_HEAD; geometric fidelity matters more than
     * texture choice for tuning).
     */
    public static List<ItemDisplay> spawn(Plugin plugin, Location anchor, int gridN, Material material) {
        World world = anchor.getWorld();
        if (world == null) throw new IllegalArgumentException("Anchor has no world");

        BlockGeometry geom = BlockGeometry.axisAligned(gridN);
        Location origin = new Location(world,
                Math.floor(anchor.getX()),
                Math.floor(anchor.getY()),
                Math.floor(anchor.getZ()));

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);

        // Match FakeBlockFactory: one canonical rotation for every chunk so
        // every UV slot's normal aligns with its like-named world FaceDir.
        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));

        List<ItemDisplay> spawned = new ArrayList<>();
        for (int x = 0; x < gridN; x++) {
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    if (!isVisible(x, y, z, gridN)) continue;

                    ChunkCoord c = new ChunkCoord(x, y, z);

                    Vector3f translation = geom.translationFor(c, canonicalRotation);
                    float scale = geom.chunkScale();

                    Transformation tx = new Transformation(
                            translation,
                            new Quaternionf(),
                            new Vector3f(scale, scale, scale),
                            canonicalRotation);

                    ItemDisplay display = world.spawn(origin, ItemDisplay.class, d -> {
                        d.setItemStack(head);
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
}
