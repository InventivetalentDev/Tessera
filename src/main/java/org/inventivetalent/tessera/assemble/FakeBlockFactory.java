package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.core.*;
import org.inventivetalent.tessera.skin.HeadSkin;
import org.inventivetalent.tessera.skin.HeadsRegistry;
import org.inventivetalent.tessera.transport.DisplayHandle;
import org.inventivetalent.tessera.transport.DisplayTransport;
import org.inventivetalent.tessera.transport.TransportSession;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.inventivetalent.tessera.effect.builtin.DirectionalShrinkEffect;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spawns a {@link FakeBlock} at a world location, populated with one
 * display entity per visible sub-block chunk. Reads pre-baked skin
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
 * <p>All display entities share the entity location at the block's
 * lower-NW-down corner; per-chunk offsets live entirely in the
 * Transformation translation. This lets a single teleport of the
 * parent location move the whole FakeBlock, which v2 physics will
 * exploit.
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
     * {@link DirectionalShrinkEffect#rescaleShell}
     * undoes the contraction in lockstep with the barrier swap.
     */
    public static final float INITIAL_SHELL_COMPRESSION = 0.99f;

    /** Outer or interior chunk scheduled for lazy spawning as the wave front advances. */
    public record PendingChunkSpec(
            ChunkCoord coord,
            HeadsRegistry.Entry registryEntry,
            double t,
            boolean interior) {}

    /**
     * Result of {@link #preloadAndPending} / {@link #completePlan} — the already-spawned
     * front refs plus a sorted pending list. The {@code session} owns the front-ref entities
     * and must be passed to the {@link FakeBlock} constructor (or closed on error).
     */
    public record PreloadPlan(
            Map<ChunkCoord, ChunkRef> frontRefs,
            List<PendingChunkSpec> pendingSpecs,
            Map<ChunkCoord, Double> allOuterT,
            TransportSession session) {}

    /**
     * Pre-built context shared across a batch of
     * {@link #spawnPendingChunk(PendingSpawnContext, PendingChunkSpec, float)} calls.
     * Build once per batch via {@link #beginPendingBatch} to avoid reconstructing
     * {@link BlockGeometry} and the canonical rotation per spawn, and to share the
     * donor {@link ItemStack} clone-source across all interior chunks in the batch.
     */
    public record PendingSpawnContext(
            TransportSession session,
            Location origin,
            Quaternionf blockRotation,
            BlockGeometry geom,
            Quaternionf canonicalRotation,
            int gridN,
            ItemStack interiorBaseStack) {}

    /**
     * Result returned by {@link #preload}: the pre-spawned front-facing chunks
     * and the session that owns them. The caller must either pass the session to
     * a subsequent {@link #create} call (transferring ownership to the FakeBlock)
     * or call {@code session().close()} to clean up if the preload is discarded.
     */
    public record PreloadResult(Map<ChunkCoord, ChunkRef> chunks, TransportSession session) {}

    private final HeadItemFactory itemFactory;
    private final HeadsRegistry registry;
    private final DisplayTransport transport;

    public FakeBlockFactory(HeadItemFactory itemFactory, HeadsRegistry registry, DisplayTransport transport) {
        this.itemFactory = itemFactory;
        this.registry = registry;
        this.transport = transport;
    }

    // ── Public create overloads ───────────────────────────────────────────────

    public FakeBlock create(Player viewer, Location blockLocation, BlockKey blockKey) {
        return create(viewer, blockLocation, BakeKey.untinted(blockKey), new Quaternionf(), false, null, false);
    }

    public FakeBlock create(Player viewer, Location blockLocation, BlockKey blockKey, Quaternionf blockRotation) {
        return create(viewer, blockLocation, BakeKey.untinted(blockKey), blockRotation, false, null, false);
    }

    public FakeBlock create(Player viewer, Location blockLocation, BakeKey bakeKey, Quaternionf blockRotation) {
        return create(viewer, blockLocation, bakeKey, blockRotation, false, null, false);
    }

    public FakeBlock create(Player viewer, Location blockLocation, BlockKey blockKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        return create(viewer, blockLocation, BakeKey.untinted(blockKey), blockRotation, fillInterior, eyeDir, false);
    }

    public FakeBlock create(Player viewer, Location blockLocation, BakeKey bakeKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        return create(viewer, blockLocation, bakeKey, blockRotation, fillInterior, eyeDir, false);
    }

    /**
     * Spawn a FakeBlock with full control over shell compression. Opens a fresh transport
     * session; all entities are visible only to {@code viewer}.
     *
     * @param viewer        the player who will see the lattice
     * @param compressShell if true, spawn at {@link #INITIAL_SHELL_COMPRESSION} scale
     */
    public FakeBlock create(Player viewer, Location blockLocation, BakeKey bakeKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                            boolean compressShell) {
        World world = blockLocation.getWorld();
        if (world == null) throw new IllegalArgumentException("Location has no world");

        TransportSession session = transport.openSession(viewer, world);
        return buildFakeBlock(session, blockLocation, bakeKey, blockRotation,
                fillInterior, compressShell, Collections.emptyMap());
    }

    /**
     * Like {@link #create(Player, Location, BakeKey, Quaternionf, boolean, Vector, boolean)}
     * but reuses any pre-spawned entities from {@code existingRefs} (from a preload) rather
     * than spawning new ones for those coords. The {@code existingSession} owns those
     * pre-spawned entities and is adopted as the FakeBlock's session; remaining chunks are
     * spawned onto it.
     */
    public FakeBlock create(Player viewer, Location blockLocation, BakeKey bakeKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                            boolean compressShell, Map<ChunkCoord, ChunkRef> existingRefs,
                            TransportSession existingSession) {
        if (blockLocation.getWorld() == null) throw new IllegalArgumentException("Location has no world");
        return buildFakeBlock(existingSession, blockLocation, bakeKey, blockRotation,
                fillInterior, compressShell, existingRefs);
    }

    // ── Preload ───────────────────────────────────────────────────────────────

    /**
     * Pre-spawn the viewer-facing faces of the outer shell for eager preload.
     * A chunk is pre-spawned if any of its outward face normals opposes the eye
     * direction (i.e. the face is visible from the player's viewpoint). The
     * returned {@link PreloadResult} contains both the spawned refs and the session
     * that owns them. The caller must pass the session to a subsequent {@link #create}
     * call or close it if the preload is discarded.
     */
    public PreloadResult preload(Player viewer, Location blockLocation, BakeKey bakeKey,
                                  Quaternionf blockRotation, Vector eyeDir) {
        World world = blockLocation.getWorld();
        if (world == null) return new PreloadResult(Collections.emptyMap(), noopSession());
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Map<ChunkCoord, HeadsRegistry.Entry> chunks = registry.chunksFor(bakeKey);
        if (chunks.isEmpty()) return new PreloadResult(Collections.emptyMap(), noopSession());

        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));

        Vector3f dir = new Vector3f((float) eyeDir.getX(), (float) eyeDir.getY(),
                (float) eyeDir.getZ()).normalize();
        Vector3f localEyeDir = new Quaternionf(blockRotation).conjugate().transform(new Vector3f(dir));

        TransportSession session = transport.openSession(viewer, world);
        List<ChunkRef> refs = new ArrayList<>(chunks.size() / 2 + 1);
        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> entry : chunks.entrySet()) {
            ChunkCoord coord = entry.getKey();
            if (!isViewerFacing(coord, gridN, localEyeDir)) continue;

            ChunkRef ref = spawnChunk(session, origin, coord, entry.getValue(),
                    blockRotation, canonicalRotation, geom, gridN, INITIAL_SHELL_COMPRESSION);
            refs.add(ref);
        }

        Map<ChunkCoord, ChunkRef> result = new HashMap<>(refs.size() * 2);
        for (ChunkRef r : refs) result.put(r.coord(), r);
        return new PreloadResult(Collections.unmodifiableMap(result), session);
    }

    // ── Lazy-spawn plan methods ───────────────────────────────────────────────

    /**
     * Compute the full lazy-spawn plan for a fresh break (no pre-spawned preload).
     * Opens a new transport session; the caller must either use {@code plan.session()}
     * as the FakeBlock's session or close it on error.
     */
    public PreloadPlan preloadAndPending(Player viewer, Location blockLocation, BakeKey bakeKey,
                                          Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        World world = blockLocation.getWorld();
        if (world == null) return emptyPlan();
        return buildPlan(transport.openSession(viewer, world), blockLocation, bakeKey,
                blockRotation, fillInterior, eyeDir, Collections.emptyMap());
    }

    /**
     * Like {@link #preloadAndPending} but reuses {@code existingRefs} for coords
     * that are already spawned (eager-preload consume path) and adopts the
     * {@code existingSession} as the plan's session so all entities share one session.
     */
    public PreloadPlan completePlan(Location blockLocation, BakeKey bakeKey,
                                     Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                                     Map<ChunkCoord, ChunkRef> existingRefs,
                                     TransportSession existingSession) {
        if (blockLocation.getWorld() == null) return new PreloadPlan(Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyMap(), existingSession);
        return buildPlan(existingSession, blockLocation, bakeKey, blockRotation, fillInterior,
                eyeDir, existingRefs);
    }

    // ── Placeholder spawn (no registry) ──────────────────────────────────────

    /**
     * Like {@link #preloadAndPending} but uses a single fixed {@code placeholderHead}
     * for every chunk instead of per-chunk registry entries. All outer chunks are
     * spawned immediately (no pending list) so the result is compatible with
     * {@code BlockBreakProgressListener.buildTrackedBreak} without requiring null
     * registry entries in {@link PendingChunkSpec}.
     *
     * <p>The returned {@link PreloadPlan} has all outer chunks in {@code frontRefs},
     * an empty {@code pendingSpecs} list, and a populated {@code allOuterT} map so
     * the directional shrink wave still advances correctly.
     */
    public PreloadPlan preloadPlaceholderAndPending(Player viewer, Location blockLocation,
                                                     Quaternionf blockRotation, boolean fillInterior,
                                                     Vector eyeDir, HeadSkin placeholderHead) {
        World world = blockLocation.getWorld();
        if (world == null) return emptyPlan();
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));

        ItemStack placeholderStack = itemFactory.build(placeholderHead);

        Vector3f dir = new Vector3f((float) eyeDir.getX(), (float) eyeDir.getY(),
                (float) eyeDir.getZ()).normalize();
        Vector3f blockCenter = new Vector3f(0.5f, 0.5f, 0.5f);

        // Project all outer coords and build t map for the directional wave.
        Map<ChunkCoord, Double> rawProj = new HashMap<>();
        double minP = Double.POSITIVE_INFINITY, maxP = Double.NEGATIVE_INFINITY;
        for (int x = 0; x < gridN; x++) {
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    ChunkCoord c = new ChunkCoord(x, y, z);
                    if (outwardFacesAt(c, gridN).isEmpty()) continue;
                    Vector3f rel = geom.chunkLocalCenter(c).sub(blockCenter);
                    new Quaternionf(blockRotation).transform(rel);
                    double p = rel.dot(dir);
                    rawProj.put(c, p);
                    if (p < minP) minP = p;
                    if (p > maxP) maxP = p;
                }
            }
        }
        double range = Math.max(1e-6, maxP - minP);
        Map<ChunkCoord, Double> allOuterT = new HashMap<>(rawProj.size() * 2);
        for (var e : rawProj.entrySet()) allOuterT.put(e.getKey(), (e.getValue() - minP) / range);

        TransportSession session = transport.openSession(viewer, world);
        Map<ChunkCoord, ChunkRef> frontRefs = new HashMap<>(rawProj.size() * 2);

        for (ChunkCoord c : rawProj.keySet()) {
            ChunkRef ref = spawnPlaceholderChunk(session, origin, c, placeholderStack,
                    blockRotation, canonicalRotation, geom, gridN);
            frontRefs.put(c, ref);
        }

        return new PreloadPlan(frontRefs, Collections.emptyList(), allOuterT, session);
    }

    /**
     * Spawn a full placeholder lattice and return it as a {@link FakeBlock}.
     * Used by the POST_BREAK path in {@code BlockBreakListener} where there is no
     * progress-tracking context — the effect is applied via
     * {@link org.inventivetalent.tessera.effect.builtin.DirectionalShrinkEffect#applyTimed}.
     */
    public FakeBlock createPlaceholder(Player viewer, Location blockLocation,
                                       Quaternionf blockRotation, HeadSkin placeholderHead) {
        World world = blockLocation.getWorld();
        if (world == null) throw new IllegalArgumentException("Location has no world");
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));

        ItemStack placeholderStack = itemFactory.build(placeholderHead);
        TransportSession session = transport.openSession(viewer, world);
        List<ChunkRef> refs = new ArrayList<>();

        for (int x = 0; x < gridN; x++) {
            for (int y = 0; y < gridN; y++) {
                for (int z = 0; z < gridN; z++) {
                    ChunkCoord c = new ChunkCoord(x, y, z);
                    if (outwardFacesAt(c, gridN).isEmpty()) continue;
                    refs.add(spawnPlaceholderChunk(session, origin, c, placeholderStack,
                            blockRotation, canonicalRotation, geom, gridN));
                }
            }
        }

        return new FakeBlock(origin, BlockKey.of("tessera:placeholder"), gridN, refs,
                blockRotation, session);
    }

    private ChunkRef spawnPlaceholderChunk(TransportSession session, Location origin, ChunkCoord coord,
                                            ItemStack placeholderStack, Quaternionf blockRotation,
                                            Quaternionf canonicalRotation, BlockGeometry geom, int gridN) {
        float scale = geom.chunkScale() * INITIAL_SHELL_COMPRESSION;
        Vector3f translation = geom.translationFor(coord, canonicalRotation, scale);
        Transformation tx = new Transformation(
                translation,
                new Quaternionf(blockRotation),
                new Vector3f(scale, scale, scale),
                canonicalRotation);
        DisplayHandle handle = session.spawn(origin, placeholderStack.clone(), tx, 1.0f);
        return new ChunkRef(handle, coord, geom.chunkLocalCenter(coord),
                outwardFacesAt(coord, gridN));
    }

    /**
     * Build a {@link PendingSpawnContext} for a batch of pending chunk spawns.
     * Pass any interior spec as {@code donorSpec} so the donor ItemStack is built once
     * for the whole batch; pass {@code null} if the batch has no interior chunks.
     * The context borrows the session from {@code fakeBlock.session()}.
     */
    public PendingSpawnContext beginPendingBatch(FakeBlock fakeBlock, PendingChunkSpec donorSpec) {
        int gridN = fakeBlock.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, fakeBlock.blockRotation());
        Quaternionf canonRot = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));
        ItemStack interiorStack = donorSpec != null
                ? itemFactory.build(HeadsRegistry.toHeadSkin(donorSpec.registryEntry()))
                : null;
        return new PendingSpawnContext(fakeBlock.session(), fakeBlock.origin(),
                fakeBlock.blockRotation(), geom, canonRot, gridN, interiorStack);
    }

    /**
     * Spawn a single pending chunk using a pre-built context. Interior chunks clone the
     * context's {@code interiorBaseStack}; outer chunks delegate to {@link #spawnChunk}.
     */
    public ChunkRef spawnPendingChunk(PendingSpawnContext ctx, PendingChunkSpec spec, float shellFactor) {
        if (spec.interior()) {
            ItemStack stack = ctx.interiorBaseStack() != null
                    ? ctx.interiorBaseStack().clone()
                    : itemFactory.build(HeadsRegistry.toHeadSkin(spec.registryEntry()));
            float scale = ctx.geom().chunkScale() * shellFactor;
            Vector3f translation = ctx.geom().translationFor(spec.coord(), ctx.canonicalRotation(), scale);
            Transformation tx = new Transformation(
                    translation,
                    new Quaternionf(ctx.blockRotation()),
                    new Vector3f(scale, scale, scale),
                    ctx.canonicalRotation());
            DisplayHandle handle = ctx.session().spawn(ctx.origin(), stack, tx, 1.0f);
            return new ChunkRef(handle, spec.coord(),
                    ctx.geom().chunkLocalCenter(spec.coord()),
                    EnumSet.noneOf(FaceDir.class));
        } else {
            return spawnChunk(ctx.session(), ctx.origin(), spec.coord(), spec.registryEntry(),
                    ctx.blockRotation(), ctx.canonicalRotation(), ctx.geom(), ctx.gridN(), shellFactor);
        }
    }

    // ── Internal build helpers ────────────────────────────────────────────────

    private FakeBlock buildFakeBlock(TransportSession session,
                                     Location blockLocation, BakeKey bakeKey,
                                     Quaternionf blockRotation, boolean fillInterior,
                                     boolean compressShell,
                                     Map<ChunkCoord, ChunkRef> existingRefs) {
        World world = blockLocation.getWorld();
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
        populateOuterChunks(session, origin, geom, chunks, blockRotation, canonicalRotation,
                gridN, shellFactor, existingRefs, refs);

        if (fillInterior && gridN >= 3 && !chunks.isEmpty()) {
            spawnInteriorChunks(session, origin, geom, chunks, blockRotation,
                    canonicalRotation, gridN, refs, shellFactor);
        }

        return new FakeBlock(origin, bakeKey.block(), gridN, refs, blockRotation, session);
    }

    /**
     * Internal: build a {@link PreloadPlan} using the given session. Outer chunks are
     * partitioned into a viewer-facing immediate set (plus all alive {@code existingRefs})
     * and a pending list for back-facing chunks; interior chunks are all pending.
     * t values are globally normalised across all outer coords.
     */
    private PreloadPlan buildPlan(TransportSession session, Location blockLocation, BakeKey bakeKey,
                                   Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                                   Map<ChunkCoord, ChunkRef> existingRefs) {
        World world = blockLocation.getWorld();
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Map<ChunkCoord, HeadsRegistry.Entry> chunks = registry.chunksFor(bakeKey);
        if (chunks.isEmpty()) {
            return new PreloadPlan(Collections.emptyMap(), Collections.emptyList(),
                    Collections.emptyMap(), session);
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

        Map<ChunkCoord, Double> allOuterT = new HashMap<>(chunks.size() * 2);
        for (var e : rawProj.entrySet()) {
            allOuterT.put(e.getKey(), (e.getValue() - minP) / range);
        }

        // 2. Keep alive preloaded entities; spawn viewer-facing chunks immediately,
        // defer back-facing chunks to pendingSpecs so they appear as the wave passes them.
        Vector3f localEyeDir = new Quaternionf(blockRotation).conjugate().transform(new Vector3f(dir));
        Map<ChunkCoord, ChunkRef> frontRefs = new HashMap<>(existingRefs.size() + chunks.size());
        List<PendingChunkSpec> pending = new ArrayList<>();

        for (Map.Entry<ChunkCoord, ChunkRef> e : existingRefs.entrySet()) {
            if (e.getValue().handle().isAlive()) frontRefs.put(e.getKey(), e.getValue());
        }

        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> e : chunks.entrySet()) {
            ChunkCoord c = e.getKey();
            if (frontRefs.containsKey(c)) continue;
            if (isViewerFacing(c, gridN, localEyeDir)) {
                frontRefs.put(c, spawnChunk(session, origin, c, e.getValue(),
                        blockRotation, canonicalRotation, geom, gridN, INITIAL_SHELL_COMPRESSION));
            } else {
                pending.add(new PendingChunkSpec(c, e.getValue(), allOuterT.get(c), false));
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
                            pending.add(new PendingChunkSpec(c, donor, t, true));
                        }
                    }
                }
            }
        }

        pending.sort(Comparator.comparingDouble(PendingChunkSpec::t));
        return new PreloadPlan(frontRefs, pending, allOuterT, session);
    }

    private void populateOuterChunks(TransportSession session, Location origin, BlockGeometry geom,
                                      Map<ChunkCoord, HeadsRegistry.Entry> chunks,
                                      Quaternionf blockRotation, Quaternionf canonicalRotation,
                                      int gridN, float shellFactor,
                                      Map<ChunkCoord, ChunkRef> existingRefs,
                                      List<ChunkRef> refs) {
        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> entry : chunks.entrySet()) {
            ChunkCoord coord = entry.getKey();
            ChunkRef existing = existingRefs.get(coord);
            if (existing != null && existing.handle().isAlive()) {
                refs.add(existing);
            } else {
                refs.add(spawnChunk(session, origin, coord, entry.getValue(),
                        blockRotation, canonicalRotation, geom, gridN, shellFactor));
            }
        }
    }

    private ChunkRef spawnChunk(TransportSession session, Location origin, ChunkCoord coord,
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

        DisplayHandle handle = session.spawn(origin, itemStack, tx, 1.0f);
        return new ChunkRef(handle, coord, geom.chunkLocalCenter(coord),
                outwardFacesAt(coord, gridN));
    }

    private void spawnInteriorChunks(TransportSession session, Location origin, BlockGeometry geom,
                                      Map<ChunkCoord, HeadsRegistry.Entry> chunks,
                                      Quaternionf blockRotation, Quaternionf canonicalRotation,
                                      int gridN, List<ChunkRef> refs, float shellFactor) {
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

                    DisplayHandle handle = session.spawn(origin, donorStack.clone(), tx, 1.0f);
                    refs.add(new ChunkRef(handle, coord,
                            geom.chunkLocalCenter(coord),
                            EnumSet.noneOf(FaceDir.class)));
                }
            }
        }
    }

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

    /**
     * Returns true if any outward face of the chunk at {@code c} has a normal that
     * opposes {@code localEyeDir} (i.e. the face is visible from the viewer's side).
     * {@code localEyeDir} must already be in block-local space.
     */
    private static boolean isViewerFacing(ChunkCoord c, int gridN, Vector3f localEyeDir) {
        for (FaceDir d : FaceDir.values()) {
            if (d.isOutwardAt(c.x(), c.y(), c.z(), gridN)
                    && d.dx * localEyeDir.x + d.dy * localEyeDir.y + d.dz * localEyeDir.z < 0) {
                return true;
            }
        }
        return false;
    }

    private static PreloadPlan emptyPlan() {
        return new PreloadPlan(Collections.emptyMap(), Collections.emptyList(),
                Collections.emptyMap(), noopSession());
    }

    private static TransportSession noopSession() {
        return new TransportSession() {
            @Override public DisplayHandle spawn(Location o, ItemStack i, Transformation t, float v) {
                throw new UnsupportedOperationException();
            }
            @Override public void close() {}
        };
    }
}
