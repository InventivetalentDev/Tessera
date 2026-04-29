package io.tessera.assemble;

import io.tessera.core.*;
import io.tessera.skin.HeadSkin;
import io.tessera.skin.HeadsRegistry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
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
 * {@code L = blockRotation} (identity in v1), {@code R = canonicalRotation}
 * (a single rotation applied to every chunk that aligns each HeadFace UV
 * slot's normal with its corresponding world FaceDir),
 * {@code S = uniform chunkScale}, and
 * {@code T = blockGeometry.translationFor(coord, R)} which compensates
 * for the head's intrinsic {@code CUBE_CENTER_PRE} offset.
 *
 * <p><b>Canonical rotation:</b> the player-head ItemStack's natural render
 * applies {@code Ry(180°)} to the cube (FRONT slot, UV +Z, ends up at world
 * -Z). Applying {@code Ry(180°)} again on top cancels that, so the cube
 * renders identity in world space. The skin-region → world-direction map
 * is then determined by the vanilla head model's per-face UVs:
 * {@code TOP → +Y (up), BOTTOM → -Y (down), FRONT → +Z (south),
 * BACK → -Z (north), LEFT → +X (east), RIGHT → -X (west)}. The X axes
 * are swapped relative to slot-name intuition because the model's
 * {@code east} cube face samples the LEFT skin region (the wearer's left
 * cheek, which with Steve facing south is at +X). {@code HeadSkinPacker}
 * accounts for this: each outward FaceDir's tile is packed into the slot
 * that the model actually shows on that world direction, so every viewer
 * sees the correct tile on whichever face they look at.
 *
 * <p>Mosaikin's per-face FaceRotations table only made sense in that
 * project's "one face shown per head" model. Tessera needs all outward
 * faces of every chunk to be simultaneously correct, which only the
 * canonical rotation gives us.
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
     * @param blockKey      the namespaced ID of the block being replaced
     */
    public FakeBlock create(Location blockLocation, BlockKey blockKey) {
        return create(blockLocation, BakeKey.untinted(blockKey), new Quaternionf(), false, null);
    }

    public FakeBlock create(Location blockLocation, BlockKey blockKey, Quaternionf blockRotation) {
        return create(blockLocation, BakeKey.untinted(blockKey), blockRotation, false, null);
    }

    /**
     * Tint-aware overload: looks up chunks via {@link BakeKey} so per-tint
     * runtime variants (grass/leaves baked against a specific biome color)
     * resolve to their own chunk map rather than the canonical untinted one.
     */
    public FakeBlock create(Location blockLocation, BakeKey bakeKey, Quaternionf blockRotation) {
        return create(blockLocation, bakeKey, blockRotation, false, null);
    }

    /**
     * Untinted convenience for callers that don't need biome-tint awareness.
     * Wraps the {@code blockKey} as {@link BakeKey#untinted(BlockKey)} and
     * delegates to the {@link BakeKey}-keyed implementation.
     */
    public FakeBlock create(Location blockLocation, BlockKey blockKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        return create(blockLocation, BakeKey.untinted(blockKey), blockRotation, fillInterior, eyeDir);
    }

    /**
     * Spawn a FakeBlock with a non-identity block rotation — used to honour
     * vanilla blockstate variants (e.g. {@code oak_log[axis=x]} ships with
     * {@code x:90, y:90} relative to the canonical baked model). The
     * rotation is applied as the {@link BlockGeometry}'s {@code L} matrix:
     * the cube formation rotates as a whole, while each chunk's individual
     * canonical rotation (the {@code R} matrix) stays the same so all
     * outward faces continue to render their correct slot tile.
     *
     * <p>If {@code fillInterior} is {@code true}, the (gridN−2)³ chunks that
     * normally make up the hollow center are also spawned, restricted to the
     * half of the cube nearest the breaker (the far half is invisible until
     * the wave is mostly through, so leaving it hollow saves entities at the
     * cost of a brief peek-through near animation end). Interior chunks
     * borrow the skin of an outer face-center chunk — close enough for the
     * brief moment they're visible during the wave passage. {@code eyeDir}
     * is required when {@code fillInterior} is true; it's ignored otherwise.
     */
    public FakeBlock create(Location blockLocation, BakeKey bakeKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        World world = blockLocation.getWorld();
        if (world == null) throw new IllegalArgumentException("Location has no world");

        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);

        // Snap to block grid origin — the lower NW-down corner of the cell.
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Map<ChunkCoord, HeadsRegistry.Entry> chunks = registry.chunksFor(bakeKey);
        List<ChunkRef> refs = new ArrayList<>(chunks.size());

        // Same rotation for every chunk — see class doc. FRONT's FaceRotations
        // entry happens to be Ry(180°), which combined with the head item's
        // built-in Ry(180°) lands every UV slot on its like-named world axis.
        // HeadRotations is composed in for the per-slot debug spin knob.
        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));

        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> entry : chunks.entrySet()) {
            ChunkCoord coord = entry.getKey();
            HeadSkin head = HeadsRegistry.toHeadSkin(entry.getValue());
            ItemStack itemStack = itemFactory.build(head);

            Quaternionf faceRot = canonicalRotation;

            Vector3f translation = geom.translationFor(coord, faceRot);
            float scale = geom.chunkScale();

            Transformation tx = new Transformation(
                    translation,
                    new Quaternionf(blockRotation),       // L = block rotation (variant Ry/Rx)
                    new Vector3f(scale, scale, scale),
                    faceRot);

            ItemDisplay display = world.spawn(origin, ItemDisplay.class, d -> {
                d.setItemStack(itemStack);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setTransformation(tx);
                d.setInterpolationDuration(0);
                d.setInterpolationDelay(0);
                // Solid blocks store light-level 0 internally — the light engine only
                // records light in air/transparent spaces, not inside opaque blocks.
                // At break time the position is still solid (or air but not yet
                // propagated), so Block.getLightFromSky() / getLightFromBlocks()
                // both return 0, causing the entity to flash black. Pinning (15,15)
                // avoids the flicker; the correct fix is to delay spawn by one tick
                // so propagation completes, then omit setBrightness and let the
                // entity sample its position naturally.
//                d.setBrightness(new org.bukkit.entity.Display.Brightness(14, 14));
                d.setViewRange(1.0f);
                d.setPersistent(false);
            });

            refs.add(new ChunkRef(display, coord,
                    geom.chunkLocalCenter(coord),
                    outwardFacesAt(coord, gridN)));
        }

        if (fillInterior && gridN >= 3 && !chunks.isEmpty()) {
            spawnInteriorChunks(world, origin, geom, chunks, blockRotation,
                    canonicalRotation, gridN, eyeDir, refs);
        }

        return new FakeBlock(origin, bakeKey.block(), gridN, refs, blockRotation);
    }

    /**
     * Spawn the inner (gridN−2)³ chunks (those with no outward face) using
     * a donor outer chunk's skin. Restricts to the breaker-facing half via
     * the rotated-local-center · eyeDir projection so we only pay the entity
     * cost for chunks the player will actually see during the wave passage.
     * Identity threshold (chunks at the cube's midplane and nearer) is the
     * sweet spot — covers the visible half plus a slim safety margin.
     */
    private void spawnInteriorChunks(World world, Location origin, BlockGeometry geom,
                                     Map<ChunkCoord, HeadsRegistry.Entry> chunks,
                                     Quaternionf blockRotation, Quaternionf canonicalRotation,
                                     int gridN, Vector eyeDir,
                                     List<ChunkRef> refs) {
        HeadsRegistry.Entry donorEntry = pickInteriorDonor(chunks, gridN);
        if (donorEntry == null) return;
        HeadSkin donorSkin = HeadsRegistry.toHeadSkin(donorEntry);
        ItemStack donorStack = itemFactory.build(donorSkin);

        // Eye direction (block-rotation-aware), used to pick the half closest
        // to the breaker. If unavailable we fill everything — the caller
        // should avoid that at high gridN.
        Vector3f dir = null;
        if (eyeDir != null) {
            Vector e = eyeDir.clone().normalize();
            dir = new Vector3f((float) e.getX(), (float) e.getY(), (float) e.getZ());
        }
        Vector3f blockCenter = new Vector3f(0.5f, 0.5f, 0.5f);

        for (int x = 1; x < gridN - 1; x++) {
            for (int y = 1; y < gridN - 1; y++) {
                for (int z = 1; z < gridN - 1; z++) {
                    ChunkCoord coord = new ChunkCoord(x, y, z);
                    if (dir != null) {
                        // Same projection as ChunkWaveSampler — chunks with
                        // negative projection sit on the breaker-facing side
                        // of the cube center and are exposed early in the
                        // wave. Drop the far-side ones to keep entity count
                        // manageable.
                        Vector3f rel = geom.chunkLocalCenter(coord).sub(blockCenter);
                        blockRotation.transform(rel);
                        if (rel.dot(dir) > 0f) continue;
                    }

                    Vector3f translation = geom.translationFor(coord, canonicalRotation);
                    float scale = geom.chunkScale();
                    Transformation tx = new Transformation(
                            translation,
                            new Quaternionf(blockRotation),
                            new Vector3f(scale, scale, scale),
                            canonicalRotation);

                    ItemStack stackCopy = donorStack.clone();
                    ItemDisplay display = world.spawn(origin, ItemDisplay.class, d -> {
                        d.setItemStack(stackCopy);
                        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                        d.setTransformation(tx);
                        d.setInterpolationDuration(0);
                        d.setInterpolationDelay(0);
                        d.setViewRange(1.0f);
                        d.setPersistent(false);
                    });

                    refs.add(new ChunkRef(display, coord,
                            geom.chunkLocalCenter(coord),
                            EnumSet.noneOf(FaceDir.class)));
                }
            }
        }
    }

    /**
     * Pick a sensible donor skin for interior chunks: prefer a face-center
     * chunk (one outward face only), since its filler-tile fallback in
     * {@link io.tessera.skin.HeadSkinPacker} replicates the visible face
     * texture across all six head-skin slots — so the interior chunk
     * shows the block's surface texture from any viewing angle. Falls back
     * to the first available chunk if no face-center is found (e.g. some
     * weird gridN=2 case).
     */
    static HeadsRegistry.Entry pickInteriorDonor(Map<ChunkCoord, HeadsRegistry.Entry> chunks, int gridN) {
        HeadsRegistry.Entry fallback = null;
        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> e : chunks.entrySet()) {
            if (fallback == null) fallback = e.getValue();
            ChunkCoord c = e.getKey();
            int outward = 0;
            int last = gridN - 1;
            if (c.x() == 0 || c.x() == last) outward++;
            if (c.y() == 0 || c.y() == last) outward++;
            if (c.z() == 0 || c.z() == last) outward++;
            if (outward == 1) return e.getValue();
        }
        return fallback;
    }

    private static EnumSet<FaceDir> outwardFacesAt(ChunkCoord c, int gridN) {
        EnumSet<FaceDir> out = EnumSet.noneOf(FaceDir.class);
        for (FaceDir d : FaceDir.values()) {
            if (d.isOutwardAt(c.x(), c.y(), c.z(), gridN)) out.add(d);
        }
        return out;
    }

}
