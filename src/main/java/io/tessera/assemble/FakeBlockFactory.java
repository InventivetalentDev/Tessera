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
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
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

    /**
     * Initial size of the chunk lattice expressed as a fraction of the
     * block volume. Spawning at slightly less than 1.0 keeps the FakeBlock
     * geometrically inside the still-visible real block until the deferred
     * sendBlockChange(BARRIER) fires, eliminating the brief z-fight that
     * would otherwise be visible on the block's surfaces. The
     * 1% contraction translates to sub-pixel gaps between chunks
     * (0.0025 block units at gridN=4), well below the rendering threshold.
     * {@link io.tessera.effect.builtin.DirectionalShrinkEffect#rescaleShell}
     * undoes the contraction in lockstep with the barrier swap.
     */
    public static final float INITIAL_SHELL_COMPRESSION = 0.99f;
    /**
     * Eager-preload only pre-spawns chunks whose normalised wave position t is
     * below this threshold — the front-facing half of the block. The remaining
     * chunks are spawned on the first left-click when the full block is
     * assembled; by the time the wave reaches them the render lag has resolved.
     */
    public static final double PRELOAD_T_THRESHOLD = 0.5;

    /** Outer or interior chunk scheduled for lazy spawning as the wave front advances. */
    public record PendingChunkSpec(
            ChunkCoord coord,
            HeadsRegistry.Entry registryEntry,
            double t,
            boolean interior) {}

    /** Result of {@link #preloadAndPending} — the already-spawned front refs plus a sorted pending list. */
    public record PreloadPlan(
            Map<ChunkCoord, ChunkRef> frontRefs,
            List<PendingChunkSpec> pendingSpecs,
            Map<ChunkCoord, Double> allOuterT) {}

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
        return create(blockLocation, BakeKey.untinted(blockKey), new Quaternionf(), false, null, false);
    }

    public FakeBlock create(Location blockLocation, BlockKey blockKey, Quaternionf blockRotation) {
        return create(blockLocation, BakeKey.untinted(blockKey), blockRotation, false, null, false);
    }

    /**
     * Tint-aware 3-arg overload: looks up chunks via {@link BakeKey} so
     * per-tint runtime variants (grass/leaves baked against a specific
     * biome color) resolve to their own chunk map rather than the canonical
     * untinted one.
     */
    public FakeBlock create(Location blockLocation, BakeKey bakeKey, Quaternionf blockRotation) {
        return create(blockLocation, bakeKey, blockRotation, false, null, false);
    }

    /**
     * Untinted convenience for callers that don't need biome-tint awareness
     * (e.g. {@code /tessera test}). Wraps {@code blockKey} as
     * {@link BakeKey#untinted(BlockKey)} and delegates to the canonical
     * {@link BakeKey}-keyed implementation.
     */
    public FakeBlock create(Location blockLocation, BlockKey blockKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        return create(blockLocation, BakeKey.untinted(blockKey), blockRotation, fillInterior, eyeDir, false);
    }

    /**
     * Tint-aware 5-arg overload: post-break path uses this since the real
     * block is already gone by then and there's no surface to z-fight with,
     * so {@code compressShell} stays false.
     */
    public FakeBlock create(Location blockLocation, BakeKey bakeKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        return create(blockLocation, bakeKey, blockRotation, fillInterior, eyeDir, false);
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
     *
     * <p>If {@code compressShell} is {@code true}, every chunk is spawned at
     * {@link #INITIAL_SHELL_COMPRESSION} of its full size — used by the
     * progress-driven break path so the lattice fits inside the still-
     * visible real block during the deferred-barrier-swap window. Callers
     * are responsible for undoing the compression when appropriate via
     * {@link io.tessera.effect.builtin.DirectionalShrinkEffect#rescaleShell}.
     */
    public FakeBlock create(Location blockLocation, BakeKey bakeKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                            boolean compressShell) {
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

        float shellFactor = compressShell ? INITIAL_SHELL_COMPRESSION : 1f;
        populateOuterChunks(world, origin, geom, chunks, blockRotation, canonicalRotation,
                gridN, shellFactor, Collections.emptyMap(), refs);

        if (fillInterior && gridN >= 3 && !chunks.isEmpty()) {
            spawnInteriorChunks(world, origin, geom, chunks, blockRotation,
                    canonicalRotation, gridN, eyeDir, refs, shellFactor);
        }

        return new FakeBlock(origin, bakeKey.block(), gridN, refs, blockRotation);
    }

    /**
     * Pre-spawn only the front-facing half of the outer shell (chunks with
     * wave position t &lt; {@link #PRELOAD_T_THRESHOLD}) for eager preload. The
     * returned map is keyed by {@link ChunkCoord} so the consume path can
     * pass it straight into
     * {@link #create(Location, BakeKey, Quaternionf, boolean, Vector, boolean, Map)}.
     */
    public Map<ChunkCoord, ChunkRef> preload(Location blockLocation, BakeKey bakeKey,
                                              Quaternionf blockRotation, Vector eyeDir) {
        World world = blockLocation.getWorld();
        if (world == null) return Collections.emptyMap();
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Map<ChunkCoord, HeadsRegistry.Entry> chunks = registry.chunksFor(bakeKey);
        if (chunks.isEmpty()) return Collections.emptyMap();

        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));
        float shellFactor = INITIAL_SHELL_COMPRESSION;

        // Compute normalised t values so we can filter to the front half.
        Vector3f dir = new Vector3f((float) eyeDir.getX(), (float) eyeDir.getY(),
                (float) eyeDir.getZ()).normalize();
        Vector3f blockCenter = new Vector3f(0.5f, 0.5f, 0.5f);
        double minP = Double.POSITIVE_INFINITY, maxP = Double.NEGATIVE_INFINITY;
        for (ChunkCoord coord : chunks.keySet()) {
            Vector3f rel = geom.chunkLocalCenter(coord).sub(blockCenter);
            new Quaternionf(blockRotation).transform(rel);
            double p = rel.dot(dir);
            if (p < minP) minP = p;
            if (p > maxP) maxP = p;
        }
        double range = Math.max(1e-6, maxP - minP);

        List<ChunkRef> refs = new ArrayList<>(chunks.size() / 2 + 1);
        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> entry : chunks.entrySet()) {
            ChunkCoord coord = entry.getKey();
            Vector3f rel = geom.chunkLocalCenter(coord).sub(blockCenter);
            new Quaternionf(blockRotation).transform(rel);
            double t = (rel.dot(dir) - minP) / range;
            if (t > PRELOAD_T_THRESHOLD) continue;

            ChunkRef ref = spawnChunk(world, origin, coord, entry.getValue(),
                    blockRotation, canonicalRotation, geom, gridN, shellFactor);
            refs.add(ref);
        }

        Map<ChunkCoord, ChunkRef> result = new HashMap<>(refs.size() * 2);
        for (ChunkRef r : refs) result.put(r.coord(), r);
        return result;
    }

    /**
     * Compute the full lazy-spawn plan: spawn the front half of outer chunks,
     * return the back half + all interior chunks as sorted pending specs.
     * t values are globally normalised across all outer coords so spawned and
     * pending chunks share a consistent wave-position scale.
     */
    public PreloadPlan preloadAndPending(Location blockLocation, BakeKey bakeKey,
                                          Quaternionf blockRotation,
                                          boolean fillInterior, Vector eyeDir) {
        return completePlan(blockLocation, bakeKey, blockRotation, fillInterior,
                eyeDir, Collections.emptyMap());
    }

    /**
     * Like {@link #preloadAndPending} but reuses {@code existingFrontRefs} for
     * coords that are already spawned (eager-preload consume path). Fresh outer
     * entities are spawned for any front-half coord NOT present in the map.
     */
    public PreloadPlan completePlan(Location blockLocation, BakeKey bakeKey,
                                     Quaternionf blockRotation, boolean fillInterior,
                                     Vector eyeDir,
                                     Map<ChunkCoord, ChunkRef> existingFrontRefs) {
        World world = blockLocation.getWorld();
        if (world == null) {
            return new PreloadPlan(Collections.emptyMap(),
                    Collections.emptyList(), Collections.emptyMap());
        }
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Map<ChunkCoord, HeadsRegistry.Entry> chunks = registry.chunksFor(bakeKey);
        if (chunks.isEmpty()) {
            return new PreloadPlan(Collections.emptyMap(),
                    Collections.emptyList(), Collections.emptyMap());
        }

        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));

        // 1. Project all outer coords; capture global min/max for t normalisation.
        Vector3f dir = new Vector3f((float) eyeDir.getX(), (float) eyeDir.getY(),
                (float) eyeDir.getZ()).normalize();
        Vector3f blockCenter = new Vector3f(0.5f, 0.5f, 0.5f);
        Map<ChunkCoord, Double> rawProj = new HashMap<>(chunks.size() * 2);
        double minP = Double.POSITIVE_INFINITY, maxP = Double.NEGATIVE_INFINITY;
        for (ChunkCoord c : chunks.keySet()) {
            Vector3f rel = geom.chunkLocalCenter(c).sub(blockCenter);
            new Quaternionf(blockRotation).transform(rel);
            double p = rel.dot(dir);
            rawProj.put(c, p);
            if (p < minP) minP = p;
            if (p > maxP) maxP = p;
        }
        double range = Math.max(1e-6, maxP - minP);

        // Normalised t for all outer coords (returned to caller for array pre-population).
        Map<ChunkCoord, Double> allOuterT = new HashMap<>(chunks.size() * 2);
        for (var e : rawProj.entrySet()) {
            allOuterT.put(e.getKey(), (e.getValue() - minP) / range);
        }

        // 2. Spawn ALL outer chunks at consume time. Back-half outer chunks used to
        // be deferred as pending, but that caused a bad visual: the player looks
        // through shrinking front layers to full-scale back layers, so the block
        // appears intact until the last moment. Spawning everything up front lets
        // the full shell animate from the first tick. The eager-preload already
        // rendered the front-half at aim time; only the back-half needs fresh spawns.
        // Interior fill chunks remain lazy (they're never directly visible through
        // the outer shell and the tent function keeps them brief anyway).
        Map<ChunkCoord, ChunkRef> frontRefs = new HashMap<>(chunks.size());
        List<PendingChunkSpec> pending = new ArrayList<>();
        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> e : chunks.entrySet()) {
            ChunkCoord c = e.getKey();
            ChunkRef existing = existingFrontRefs.get(c);
            if (existing != null && !existing.display().isDead()) {
                frontRefs.put(c, existing);
            } else {
                frontRefs.put(c, spawnChunk(world, origin, c, e.getValue(),
                        blockRotation, canonicalRotation, geom, gridN,
                        INITIAL_SHELL_COMPRESSION));
            }
        }

        // 3. Interior chunks: all pending, using the same global t scale.
        if (fillInterior && gridN >= 3) {
            HeadsRegistry.Entry donor = pickInteriorDonor(chunks, gridN);
            if (donor != null) {
                for (int x = 1; x < gridN - 1; x++) {
                    for (int y = 1; y < gridN - 1; y++) {
                        for (int z = 1; z < gridN - 1; z++) {
                            ChunkCoord c = new ChunkCoord(x, y, z);
                            Vector3f rel = geom.chunkLocalCenter(c).sub(blockCenter);
                            new Quaternionf(blockRotation).transform(rel);
                            double p = rel.dot(dir);
                            double t = (p - minP) / range;
                            pending.add(new PendingChunkSpec(c, donor, t, /*interior=*/ true));
                        }
                    }
                }
            }
        }

        pending.sort((a, b) -> Double.compare(a.t(), b.t()));

        // Despawn any pre-spawned entity whose coord shifted to the back-half because
        // the player's eyeDir changed between aim time and consume time. These entities
        // are alive but would never be included in frontRefs (their t > PRELOAD_T_THRESHOLD
        // under the new eyeDir) and therefore never reach fakeBlock.despawn().
        for (Map.Entry<ChunkCoord, ChunkRef> e : existingFrontRefs.entrySet()) {
            if (!frontRefs.containsKey(e.getKey()) && !e.getValue().display().isDead()) {
                e.getValue().display().remove();
            }
        }

        return new PreloadPlan(frontRefs, pending, allOuterT);
    }

    /**
     * Spawn a single chunk entity from a {@link PendingChunkSpec}. Used by the
     * progress listener when the wave front approaches a not-yet-spawned chunk.
     */
    public ChunkRef spawnPendingChunk(Location originOfBlock, PendingChunkSpec spec,
                                       Quaternionf blockRotation, float shellFactor) {
        World world = originOfBlock.getWorld();
        if (world == null) throw new IllegalArgumentException("Origin has no world");
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));

        if (spec.interior()) {
            HeadSkin donorSkin = HeadsRegistry.toHeadSkin(spec.registryEntry());
            ItemStack stack = itemFactory.build(donorSkin);
            // Interior chunks spawn at shell-compressed scale; the tent formula
            // in applyAtProgress will drive the visible scale from there.
            float scale = geom.chunkScale() * shellFactor;
            Vector3f translation = geom.translationFor(spec.coord(), canonicalRotation, scale);
            Transformation tx = new Transformation(
                    translation,
                    new Quaternionf(blockRotation),
                    new Vector3f(scale, scale, scale),
                    canonicalRotation);
            ItemStack stackCopy = stack.clone();
            ItemDisplay display = world.spawn(originOfBlock, ItemDisplay.class, d -> {
                d.setItemStack(stackCopy);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setTransformation(tx);
                d.setInterpolationDuration(0);
                d.setInterpolationDelay(0);
                d.setViewRange(1.0f);
                d.setPersistent(false);
            });
            return new ChunkRef(display, spec.coord(),
                    geom.chunkLocalCenter(spec.coord()),
                    EnumSet.noneOf(FaceDir.class));
        } else {
            return spawnChunk(world, originOfBlock, spec.coord(), spec.registryEntry(),
                    blockRotation, canonicalRotation, geom, gridN, shellFactor);
        }
    }

    /**
     * Like {@link #create(Location, BakeKey, Quaternionf, boolean, Vector, boolean)}
     * but reuses any pre-spawned entities from {@code existingRefs} rather than
     * spawning new ones for those coords. Chunks not present in
     * {@code existingRefs} are spawned fresh at the shell-compressed scale.
     * Used by the eager-preload consume path so the pre-warmed front-facing
     * entities are incorporated into the full FakeBlock without a respawn.
     */
    public FakeBlock create(Location blockLocation, BakeKey bakeKey,
                             Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                             boolean compressShell, Map<ChunkCoord, ChunkRef> existingRefs) {
        World world = blockLocation.getWorld();
        if (world == null) throw new IllegalArgumentException("Location has no world");
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Map<ChunkCoord, HeadsRegistry.Entry> chunks = registry.chunksFor(bakeKey);
        List<ChunkRef> refs = new ArrayList<>(chunks.size());

        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));
        float shellFactor = compressShell ? INITIAL_SHELL_COMPRESSION : 1f;

        populateOuterChunks(world, origin, geom, chunks, blockRotation, canonicalRotation,
                gridN, shellFactor, existingRefs, refs);

        if (fillInterior && gridN >= 3 && !chunks.isEmpty()) {
            spawnInteriorChunks(world, origin, geom, chunks, blockRotation,
                    canonicalRotation, gridN, eyeDir, refs, shellFactor);
        }
        return new FakeBlock(origin, bakeKey.block(), gridN, refs, blockRotation);
    }

    /**
     * Populate {@code refs} with outer shell chunk refs, reusing entities from
     * {@code existingRefs} where available and spawning fresh ones for the rest.
     */
    private void populateOuterChunks(World world, Location origin, BlockGeometry geom,
                                      Map<ChunkCoord, HeadsRegistry.Entry> chunks,
                                      Quaternionf blockRotation, Quaternionf canonicalRotation,
                                      int gridN, float shellFactor,
                                      Map<ChunkCoord, ChunkRef> existingRefs,
                                      List<ChunkRef> refs) {
        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> entry : chunks.entrySet()) {
            ChunkCoord coord = entry.getKey();
            ChunkRef existing = existingRefs.get(coord);
            if (existing != null && !existing.display().isDead()) {
                refs.add(existing);
            } else {
                refs.add(spawnChunk(world, origin, coord, entry.getValue(),
                        blockRotation, canonicalRotation, geom, gridN, shellFactor));
            }
        }
    }

    /**
     * TODO(perf): Replace world.spawn() calls throughout this class with direct
     * NMS/packet-based entity spawning. Bukkit's world.spawn() runs the full
     * server-side entity lifecycle (chunk tracking, persistence, AI ticking),
     * which is expensive when spawning dozens to hundreds of entities per block
     * break. A packet-only path would:
     *   - Send AddEntityPacket / SetEntityDataPacket directly to nearby players
     *   - Skip the entity being registered in the world's entity store
     *   - Skip entity tracker updates (no per-tick tracking overhead)
     *   - Allow near-instant despawn by sending RemoveEntitiesPacket
     * This would also eliminate the server-side cost spike seen when the reverse
     * animation despawns large numbers of ItemDisplays simultaneously.
     */

    /** Spawn a single outer-shell {@link ItemDisplay} chunk and return its {@link ChunkRef}. */
    private ChunkRef spawnChunk(World world, Location origin, ChunkCoord coord,
                                 HeadsRegistry.Entry entry, Quaternionf blockRotation,
                                 Quaternionf canonicalRotation, BlockGeometry geom,
                                 int gridN, float shellFactor) {
        HeadSkin head = HeadsRegistry.toHeadSkin(entry);
        ItemStack itemStack = itemFactory.build(head);
        float scale = geom.chunkScale() * shellFactor;
        Vector3f translation = geom.translationFor(coord, canonicalRotation, scale);
        Transformation tx = new Transformation(
                translation,
                new Quaternionf(blockRotation),
                new Vector3f(scale, scale, scale),
                canonicalRotation);
        ItemDisplay display = world.spawn(origin, ItemDisplay.class, d -> {
            d.setItemStack(itemStack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(tx);
            d.setInterpolationDuration(0);
            d.setInterpolationDelay(0);
            d.setViewRange(1.0f);
            d.setPersistent(false);
        });
        return new ChunkRef(display, coord, geom.chunkLocalCenter(coord),
                outwardFacesAt(coord, gridN));
    }

    /**
     * Spawn the inner (gridN−2)³ chunks (those with no outward face) using
     * a donor outer chunk's skin. All interior chunks are spawned — the
     * previous near-half filter left far-side interior chunks absent, which
     * was visible as empty space once the wave passed the block midpoint.
     */
    private void spawnInteriorChunks(World world, Location origin, BlockGeometry geom,
                                     Map<ChunkCoord, HeadsRegistry.Entry> chunks,
                                     Quaternionf blockRotation, Quaternionf canonicalRotation,
                                     int gridN, Vector eyeDir,
                                     List<ChunkRef> refs, float shellFactor) {
        HeadsRegistry.Entry donorEntry = pickInteriorDonor(chunks, gridN);
        if (donorEntry == null) return;
        HeadSkin donorSkin = HeadsRegistry.toHeadSkin(donorEntry);
        ItemStack donorStack = itemFactory.build(donorSkin);

        for (int x = 1; x < gridN - 1; x++) {
            for (int y = 1; y < gridN - 1; y++) {
                for (int z = 1; z < gridN - 1; z++) {
                    ChunkCoord coord = new ChunkCoord(x, y, z);

                    float scale = geom.chunkScale() * shellFactor;
                    Vector3f translation = geom.translationFor(coord, canonicalRotation, scale);
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
