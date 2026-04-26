package io.tessera.assemble;

import io.tessera.core.BlockKey;
import io.tessera.core.ChunkCoord;
import io.tessera.core.ChunkRef;
import io.tessera.core.FaceDir;
import io.tessera.core.FakeBlock;
import io.tessera.core.HeadFace;
import io.tessera.skin.HeadSkin;
import io.tessera.skin.HeadSkinPacker;
import io.tessera.skin.HeadsRegistry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Spawns a {@link FakeBlock} at a world location, populated with one
 * {@link ItemDisplay} per visible sub-block chunk. Reads pre-baked skin
 * texture data from {@link HeadsRegistry}; never blocks on MineSkin.
 *
 * <p>Bukkit transform composition (see {@link BlockGeometry}):
 * {@code worldVertex = entityLocation + T + L*S*R*v}. We set
 * {@code L = blockRotation} (identity in v1), {@code R = faceRotation}
 * (picks which head-cube face is shown), {@code S = uniform chunkScale},
 * and {@code T = blockGeometry.translationFor(coord, R)} which compensates
 * for the head's intrinsic {@code CUBE_CENTER_PRE} offset.
 *
 * <p>All {@link ItemDisplay}s share the entity location at the block's
 * lower-NW-down corner; per-chunk offsets live entirely in the
 * Transformation translation. This lets a single
 * {@link Location#teleport} on the parent location move the whole
 * FakeBlock, which v2 physics will exploit.
 */
public final class FakeBlockFactory {

    private final HeadItemFactory itemFactory;
    private final HeadsRegistry registry;

    public FakeBlockFactory(HeadItemFactory itemFactory, HeadsRegistry registry) {
        this.itemFactory = itemFactory;
        this.registry = registry;
    }

    /**
     * Spawn a FakeBlock for the block at {@code blockLocation}'s grid cell.
     * The block is identified by {@code blockKey}; the registry must contain
     * it (the listener checks {@code registry.has(blockKey)} before calling).
     *
     * @param blockLocation the world location of the block (any coordinate inside the cell works; we floor it)
     * @param blockKey the namespaced ID of the block being replaced
     */
    public FakeBlock create(Location blockLocation, BlockKey blockKey) {
        World world = blockLocation.getWorld();
        if (world == null) throw new IllegalArgumentException("Location has no world");

        int gridN = registry.gridN();
        BlockGeometry geom = BlockGeometry.axisAligned(gridN);

        // Snap to block grid origin — the lower NW-down corner of the cell.
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Map<ChunkCoord, HeadsRegistry.Entry> chunks = registry.chunksFor(blockKey);
        List<ChunkRef> refs = new ArrayList<>(chunks.size());

        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> entry : chunks.entrySet()) {
            ChunkCoord coord = entry.getKey();
            HeadSkin head = HeadsRegistry.toHeadSkin(entry.getValue());
            ItemStack itemStack = itemFactory.build(head);

            FaceDir primary = pickPrimaryFace(coord, gridN);
            HeadFace headFace = HeadSkinPacker.faceDirToHeadFace(primary);
            Quaternionf faceRot = HeadRotations.compose(headFace, primary, FaceRotations.of(headFace));

            Vector3f translation = geom.translationFor(coord, faceRot);
            float scale = geom.chunkScale();

            Transformation tx = new Transformation(
                    translation,
                    new Quaternionf(),                    // leftRotation = identity (block rotation)
                    new Vector3f(scale, scale, scale),
                    faceRot);

            ItemDisplay display = world.spawn(origin, ItemDisplay.class, d -> {
                d.setItemStack(itemStack);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setTransformation(tx);
                d.setInterpolationDuration(0);
                d.setInterpolationDelay(0);
                d.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                d.setViewRange(1.0f);
                d.setPersistent(false);
            });

            refs.add(new ChunkRef(display, coord,
                    geom.chunkLocalCenter(coord),
                    outwardFacesAt(coord, gridN)));
        }

        return new FakeBlock(origin, blockKey, gridN, refs);
    }

    /**
     * Pick the {@link FaceDir} whose tile the chunk should "advertise" via
     * its head-cube rotation. For face-center chunks (1 outward face) it's
     * that face. For edge/corner chunks we pick deterministically — the
     * head's adjacent slots will show the other outward tiles correctly,
     * but the face rotation only orients one of them as "front".
     *
     * <p>Order matches {@link FaceDir} declaration: vertical (DOWN, UP) is
     * preferred so blocks broken from above see the top face oriented
     * outward. v1: simple iteration; v2 may pick by player view direction.
     */
    private static FaceDir pickPrimaryFace(ChunkCoord coord, int gridN) {
        for (FaceDir d : FaceDir.values()) {
            if (d.isOutwardAt(coord.x(), coord.y(), coord.z(), gridN)) return d;
        }
        throw new IllegalStateException("Interior chunk should have been filtered out: " + coord);
    }

    private static EnumSet<FaceDir> outwardFacesAt(ChunkCoord c, int gridN) {
        EnumSet<FaceDir> out = EnumSet.noneOf(FaceDir.class);
        for (FaceDir d : FaceDir.values()) {
            if (d.isOutwardAt(c.x(), c.y(), c.z(), gridN)) out.add(d);
        }
        return out;
    }

}
