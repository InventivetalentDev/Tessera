package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.assets.model.BlockModel;
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
 * texture data + outward-face masks from {@link HeadsRegistry}; never
 * blocks on MineSkin.
 *
 * <p>Multi-shape: callers pass a {@code shapeKey} which the registry uses
 * to look up the matching shape's chunk lattice. For cube blocks pass
 * {@link BlockModel#DEFAULT_SHAPE_KEY}. For corner stairs etc. pass the
 * shape key returned by {@link HeadsRegistry#variantBindingFor}. Per-chunk
 * outward face sets are read from the registry rather than recomputed
 * here — that's what makes runtime shape-agnostic.
 *
 * <p>Bukkit transform composition (see {@link BlockGeometry}):
 * {@code worldVertex = entityLocation + T + L*S*R*v}. We set
 * {@code L = blockRotation}, {@code R = canonicalRotation},
 * {@code S = uniform chunkScale}, and
 * {@code T = blockGeometry.translationFor(coord, R)}.
 */
public final class FakeBlockFactory {

    /**
     * Initial size of the chunk lattice expressed as a fraction of the
     * block volume. Spawning at slightly less than 1.0 keeps the FakeBlock
     * geometrically inside the still-visible real block until the deferred
     * sendBlockChange(BARRIER) fires, eliminating the brief z-fight that
     * would otherwise be visible on the block's surfaces.
     */
    public static final float INITIAL_SHELL_COMPRESSION = 0.99f;

    /** Outer or interior chunk scheduled for lazy spawning as the wave front advances. */
    public record PendingChunkSpec(
            ChunkCoord coord,
            HeadsRegistry.ChunkEntry registryEntry,
            double t,
            boolean interior) {}

    public record PreloadPlan(
            Map<ChunkCoord, ChunkRef> frontRefs,
            List<PendingChunkSpec> pendingSpecs,
            Map<ChunkCoord, Double> allOuterT,
            TransportSession session) {}

    public record PendingSpawnContext(
            TransportSession session,
            Location origin,
            Quaternionf blockRotation,
            BlockGeometry geom,
            Quaternionf canonicalRotation,
            int gridN,
            ItemStack interiorBaseStack) {}

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
        return create(viewer, blockLocation, BakeKey.untinted(blockKey),
                registry.defaultShapeFor(BakeKey.untinted(blockKey)),
                new Quaternionf(), false, null, false);
    }

    public FakeBlock create(Player viewer, Location blockLocation, BlockKey blockKey, Quaternionf blockRotation) {
        BakeKey bakeKey = BakeKey.untinted(blockKey);
        return create(viewer, blockLocation, bakeKey, registry.defaultShapeFor(bakeKey),
                blockRotation, false, null, false);
    }

    public FakeBlock create(Player viewer, Location blockLocation, BakeKey bakeKey, Quaternionf blockRotation) {
        return create(viewer, blockLocation, bakeKey, registry.defaultShapeFor(bakeKey),
                blockRotation, false, null, false);
    }

    public FakeBlock create(Player viewer, Location blockLocation, BlockKey blockKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        BakeKey bakeKey = BakeKey.untinted(blockKey);
        return create(viewer, blockLocation, bakeKey, registry.defaultShapeFor(bakeKey),
                blockRotation, fillInterior, eyeDir, false);
    }

    public FakeBlock create(Player viewer, Location blockLocation, BakeKey bakeKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        return create(viewer, blockLocation, bakeKey, registry.defaultShapeFor(bakeKey),
                blockRotation, fillInterior, eyeDir, false);
    }

    /**
     * Full-control overload. Opens a fresh transport session; all entities
     * are visible only to {@code viewer}.
     */
    public FakeBlock create(Player viewer, Location blockLocation, BakeKey bakeKey, String shapeKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                            boolean compressShell) {
        World world = blockLocation.getWorld();
        if (world == null) throw new IllegalArgumentException("Location has no world");

        TransportSession session = transport.openSession(viewer, world);
        return buildFakeBlock(session, blockLocation, bakeKey, shapeKey, blockRotation,
                fillInterior, compressShell, Collections.emptyMap());
    }

    /**
     * Like {@link #create(Player, Location, BakeKey, String, Quaternionf, boolean, Vector, boolean)}
     * but reuses any pre-spawned entities from {@code existingRefs}.
     */
    public FakeBlock create(Player viewer, Location blockLocation, BakeKey bakeKey, String shapeKey,
                            Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                            boolean compressShell, Map<ChunkCoord, ChunkRef> existingRefs,
                            TransportSession existingSession) {
        if (blockLocation.getWorld() == null) throw new IllegalArgumentException("Location has no world");
        return buildFakeBlock(existingSession, blockLocation, bakeKey, shapeKey, blockRotation,
                fillInterior, compressShell, existingRefs);
    }

    // ── Preload ───────────────────────────────────────────────────────────────

    /**
     * Pre-spawn the viewer-facing faces of the outer shell for eager preload.
     * The shape's per-chunk outward-face mask drives which faces are viewer-
     * facing.
     */
    public PreloadResult preload(Player viewer, Location blockLocation, BakeKey bakeKey, String shapeKey,
                                  Quaternionf blockRotation, Vector eyeDir) {
        World world = blockLocation.getWorld();
        if (world == null) return new PreloadResult(Collections.emptyMap(), noopSession());
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks = registry.chunksFor(bakeKey, shapeKey);
        if (chunks.isEmpty()) return new PreloadResult(Collections.emptyMap(), noopSession());

        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));

        Vector3f dir = new Vector3f((float) eyeDir.getX(), (float) eyeDir.getY(),
                (float) eyeDir.getZ()).normalize();
        Vector3f localEyeDir = new Quaternionf(blockRotation).conjugate().transform(new Vector3f(dir));

        TransportSession session = transport.openSession(viewer, world);
        List<ChunkRef> refs = new ArrayList<>(chunks.size() / 2 + 1);
        for (Map.Entry<ChunkCoord, HeadsRegistry.ChunkEntry> entry : chunks.entrySet()) {
            ChunkCoord coord = entry.getKey();
            EnumSet<FaceDir> outward = entry.getValue().outwardFaces();
            if (!isViewerFacing(outward, localEyeDir)) continue;

            ChunkRef ref = spawnChunk(session, origin, coord, entry.getValue(),
                    blockRotation, canonicalRotation, geom, INITIAL_SHELL_COMPRESSION);
            refs.add(ref);
        }

        Map<ChunkCoord, ChunkRef> result = new HashMap<>(refs.size() * 2);
        for (ChunkRef r : refs) result.put(r.coord(), r);
        return new PreloadResult(Collections.unmodifiableMap(result), session);
    }

    // ── Lazy-spawn plan methods ───────────────────────────────────────────────

    public PreloadPlan preloadAndPending(Player viewer, Location blockLocation, BakeKey bakeKey, String shapeKey,
                                          Quaternionf blockRotation, boolean fillInterior, Vector eyeDir) {
        World world = blockLocation.getWorld();
        if (world == null) return emptyPlan();
        return buildPlan(transport.openSession(viewer, world), blockLocation, bakeKey, shapeKey,
                blockRotation, fillInterior, eyeDir, Collections.emptyMap());
    }

    public PreloadPlan completePlan(Location blockLocation, BakeKey bakeKey, String shapeKey,
                                     Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                                     Map<ChunkCoord, ChunkRef> existingRefs,
                                     TransportSession existingSession) {
        if (blockLocation.getWorld() == null) return new PreloadPlan(Collections.emptyMap(),
                Collections.emptyList(), Collections.emptyMap(), existingSession);
        return buildPlan(existingSession, blockLocation, bakeKey, shapeKey, blockRotation, fillInterior,
                eyeDir, existingRefs);
    }

    public PendingSpawnContext beginPendingBatch(FakeBlock fakeBlock, PendingChunkSpec donorSpec) {
        int gridN = fakeBlock.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, fakeBlock.blockRotation());
        Quaternionf canonRot = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));
        ItemStack interiorStack = donorSpec != null
                ? itemFactory.build(HeadsRegistry.toHeadSkin(donorSpec.registryEntry().skin()))
                : null;
        return new PendingSpawnContext(fakeBlock.session(), fakeBlock.origin(),
                fakeBlock.blockRotation(), geom, canonRot, gridN, interiorStack);
    }

    public ChunkRef spawnPendingChunk(PendingSpawnContext ctx, PendingChunkSpec spec, float shellFactor) {
        if (spec.interior()) {
            ItemStack stack = ctx.interiorBaseStack() != null
                    ? ctx.interiorBaseStack().clone()
                    : itemFactory.build(HeadsRegistry.toHeadSkin(spec.registryEntry().skin()));
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
                    ctx.blockRotation(), ctx.canonicalRotation(), ctx.geom(), shellFactor);
        }
    }

    // ── Internal build helpers ────────────────────────────────────────────────

    private FakeBlock buildFakeBlock(TransportSession session,
                                     Location blockLocation, BakeKey bakeKey, String shapeKey,
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

        Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks = registry.chunksFor(bakeKey, shapeKey);
        List<ChunkRef> refs = new ArrayList<>(chunks.size());

        Quaternionf canonicalRotation = HeadRotations.compose(
                HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));

        float shellFactor = compressShell ? INITIAL_SHELL_COMPRESSION : 1f;
        populateOuterChunks(session, origin, geom, chunks, blockRotation, canonicalRotation,
                shellFactor, existingRefs, refs);

        if (fillInterior && gridN >= 3 && !chunks.isEmpty()) {
            spawnInteriorChunks(session, origin, geom, chunks, blockRotation,
                    canonicalRotation, gridN, refs, shellFactor);
        }

        return new FakeBlock(origin, bakeKey.block(), gridN, refs, blockRotation, session);
    }

    private PreloadPlan buildPlan(TransportSession session, Location blockLocation, BakeKey bakeKey, String shapeKey,
                                   Quaternionf blockRotation, boolean fillInterior, Vector eyeDir,
                                   Map<ChunkCoord, ChunkRef> existingRefs) {
        World world = blockLocation.getWorld();
        int gridN = registry.gridN();
        BlockGeometry geom = new BlockGeometry(gridN, blockRotation);
        Location origin = new Location(world,
                Math.floor(blockLocation.getX()),
                Math.floor(blockLocation.getY()),
                Math.floor(blockLocation.getZ()));

        Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks = registry.chunksFor(bakeKey, shapeKey);
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
        // defer back-facing chunks to pendingSpecs so they appear as the wave passes.
        Vector3f localEyeDir = new Quaternionf(blockRotation).conjugate().transform(new Vector3f(dir));
        Map<ChunkCoord, ChunkRef> frontRefs = new HashMap<>(existingRefs.size() + chunks.size());
        List<PendingChunkSpec> pending = new ArrayList<>();

        for (Map.Entry<ChunkCoord, ChunkRef> e : existingRefs.entrySet()) {
            if (e.getValue().handle().isAlive()) frontRefs.put(e.getKey(), e.getValue());
        }

        for (Map.Entry<ChunkCoord, HeadsRegistry.ChunkEntry> e : chunks.entrySet()) {
            ChunkCoord c = e.getKey();
            if (frontRefs.containsKey(c)) continue;
            EnumSet<FaceDir> outward = e.getValue().outwardFaces();
            if (isViewerFacing(outward, localEyeDir)) {
                frontRefs.put(c, spawnChunk(session, origin, c, e.getValue(),
                        blockRotation, canonicalRotation, geom, INITIAL_SHELL_COMPRESSION));
            } else {
                pending.add(new PendingChunkSpec(c, e.getValue(), allOuterT.get(c), false));
            }
        }

        // 3. Interior chunks: all pending, using the same global t scale. Filter by
        // shape — non-cube shapes have no meaningful interior (no coord in [1,N-1]³
        // that isn't already in the outer chunk set), so most iterations are no-ops.
        if (fillInterior && gridN >= 3) {
            HeadsRegistry.ChunkEntry donor = pickInteriorDonor(chunks, gridN);
            if (donor != null) {
                for (int x = 1; x < gridN - 1; x++) {
                    for (int y = 1; y < gridN - 1; y++) {
                        for (int z = 1; z < gridN - 1; z++) {
                            ChunkCoord c = new ChunkCoord(x, y, z);
                            // Skip cells that aren't part of this shape (sparse non-cube)
                            // or are already in the outer set.
                            if (chunks.containsKey(c)) continue;
                            // Only fill cells that are "inside" the shape — i.e.
                            // surrounded by outer chunks on all 6 sides. For
                            // cubes this is the entire (1..N-1)³ interior; for
                            // non-cubes it's typically empty.
                            if (!isInsideShape(c, chunks, gridN)) continue;
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
                                      Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks,
                                      Quaternionf blockRotation, Quaternionf canonicalRotation,
                                      float shellFactor,
                                      Map<ChunkCoord, ChunkRef> existingRefs,
                                      List<ChunkRef> refs) {
        for (Map.Entry<ChunkCoord, HeadsRegistry.ChunkEntry> entry : chunks.entrySet()) {
            ChunkCoord coord = entry.getKey();
            ChunkRef existing = existingRefs.get(coord);
            if (existing != null && existing.handle().isAlive()) {
                refs.add(existing);
            } else {
                refs.add(spawnChunk(session, origin, coord, entry.getValue(),
                        blockRotation, canonicalRotation, geom, shellFactor));
            }
        }
    }

    private ChunkRef spawnChunk(TransportSession session, Location origin, ChunkCoord coord,
                                 HeadsRegistry.ChunkEntry entry, Quaternionf blockRotation,
                                 Quaternionf canonicalRotation, BlockGeometry geom,
                                 float shellFactor) {
        HeadSkin head = HeadsRegistry.toHeadSkin(entry.skin());
        ItemStack itemStack = itemFactory.build(head);
        float scale = geom.chunkScale() * shellFactor;
        Vector3f translation = geom.translationFor(coord, canonicalRotation, scale);
        Transformation tx = new Transformation(
                translation,
                new Quaternionf(blockRotation),
                new Vector3f(scale, scale, scale),
                canonicalRotation);

        DisplayHandle handle = session.spawn(origin, itemStack, tx, 1.0f);
        return new ChunkRef(handle, coord, geom.chunkLocalCenter(coord), entry.outwardFaces());
    }

    private void spawnInteriorChunks(TransportSession session, Location origin, BlockGeometry geom,
                                      Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks,
                                      Quaternionf blockRotation, Quaternionf canonicalRotation,
                                      int gridN, List<ChunkRef> refs, float shellFactor) {
        HeadsRegistry.ChunkEntry donorEntry = pickInteriorDonor(chunks, gridN);
        if (donorEntry == null) return;
        HeadSkin donorSkin = HeadsRegistry.toHeadSkin(donorEntry.skin());
        ItemStack donorStack = itemFactory.build(donorSkin);

        for (int x = 1; x < gridN - 1; x++) {
            for (int y = 1; y < gridN - 1; y++) {
                for (int z = 1; z < gridN - 1; z++) {
                    ChunkCoord coord = new ChunkCoord(x, y, z);
                    if (chunks.containsKey(coord)) continue;
                    if (!isInsideShape(coord, chunks, gridN)) continue;

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

    static HeadsRegistry.ChunkEntry pickInteriorDonor(Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks, int gridN) {
        HeadsRegistry.ChunkEntry fallback = null;
        for (Map.Entry<ChunkCoord, HeadsRegistry.ChunkEntry> e : chunks.entrySet()) {
            if (fallback == null) fallback = e.getValue();
            int outwardCount = e.getValue().outwardFaces().size();
            if (outwardCount == 1) return e.getValue();
        }
        return fallback;
    }

    /**
     * True iff every 6-neighbor of {@code c} is either out-of-bounds or
     * an outer chunk in {@code chunks}. This catches "buried" cells —
     * cube interior for a full cube; nothing for slabs/stairs. Used to
     * decide which interior cells need filler donor spawns when
     * {@code fillInterior} is set.
     */
    private static boolean isInsideShape(ChunkCoord c, Map<ChunkCoord, HeadsRegistry.ChunkEntry> chunks, int gridN) {
        for (FaceDir d : FaceDir.values()) {
            int nx = c.x() + d.dx, ny = c.y() + d.dy, nz = c.z() + d.dz;
            if (nx < 0 || nx >= gridN || ny < 0 || ny >= gridN || nz < 0 || nz >= gridN) continue;
            if (!chunks.containsKey(new ChunkCoord(nx, ny, nz))) return false;
        }
        return true;
    }

    /**
     * Returns true if any outward face of the chunk has a normal that
     * opposes {@code localEyeDir} (i.e. the face is visible from the viewer).
     * {@code localEyeDir} must already be in block-local space.
     */
    private static boolean isViewerFacing(EnumSet<FaceDir> outward, Vector3f localEyeDir) {
        for (FaceDir d : outward) {
            if (d.dx * localEyeDir.x + d.dy * localEyeDir.y + d.dz * localEyeDir.z < 0) {
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
