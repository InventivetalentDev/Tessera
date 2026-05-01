package io.tessera.assemble;

import io.tessera.core.*;
import io.tessera.skin.HeadSkin;
import io.tessera.skin.HeadsRegistry;
import io.tessera.transport.DisplayHandle;
import io.tessera.transport.DisplayTransport;
import io.tessera.transport.TransportSession;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
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

    private final HeadItemFactory itemFactory;
    private final HeadsRegistry registry;
    private final DisplayTransport transport;

    public FakeBlockFactory(HeadItemFactory itemFactory, HeadsRegistry registry, DisplayTransport transport) {
        this.itemFactory = itemFactory;
        this.registry = registry;
        this.transport = transport;
    }

    /**
     * Result returned by {@link #preload}: the pre-spawned front-facing chunks
     * and the session that owns them. The caller must either pass the session to
     * a subsequent {@link #create} call (transferring ownership to the FakeBlock)
     * or call {@code session().close()} to clean up if the preload is discarded.
     */
    public record PreloadResult(Map<ChunkCoord, ChunkRef> chunks, TransportSession session) {}

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
        return buildFakeBlock(session, viewer, blockLocation, bakeKey, blockRotation,
                fillInterior, eyeDir, compressShell, Collections.emptyMap());
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
        return buildFakeBlock(existingSession, viewer, blockLocation, bakeKey, blockRotation,
                fillInterior, eyeDir, compressShell, existingRefs);
    }

    // ── Internal build helper ─────────────────────────────────────────────────

    private FakeBlock buildFakeBlock(TransportSession session, Player viewer,
                                     Location blockLocation, BakeKey bakeKey,
                                     Quaternionf blockRotation, boolean fillInterior,
                                     Vector eyeDir, boolean compressShell,
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

    // ── Preload ───────────────────────────────────────────────────────────────

    /**
     * Pre-spawn only the front-facing half of the outer shell (chunks with
     * wave position t &lt; {@link #PRELOAD_T_THRESHOLD}) for eager preload. The
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
        float shellFactor = INITIAL_SHELL_COMPRESSION;

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

        TransportSession session = transport.openSession(viewer, world);
        List<ChunkRef> refs = new ArrayList<>(chunks.size() / 2 + 1);
        for (Map.Entry<ChunkCoord, HeadsRegistry.Entry> entry : chunks.entrySet()) {
            ChunkCoord coord = entry.getKey();
            Vector3f rel = geom.chunkLocalCenter(coord).sub(blockCenter);
            new Quaternionf(blockRotation).transform(rel);
            double t = (rel.dot(dir) - minP) / range;
            if (t > PRELOAD_T_THRESHOLD) continue;

            ChunkRef ref = spawnChunk(session, origin, coord, entry.getValue(),
                    blockRotation, canonicalRotation, geom, gridN, shellFactor);
            refs.add(ref);
        }

        Map<ChunkCoord, ChunkRef> result = new HashMap<>(refs.size() * 2);
        for (ChunkRef r : refs) result.put(r.coord(), r);
        return new PreloadResult(Collections.unmodifiableMap(result), session);
    }

    // ── Internal spawn helpers ────────────────────────────────────────────────

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

    private static TransportSession noopSession() {
        return new TransportSession() {
            @Override public DisplayHandle spawn(Location o, ItemStack i, Transformation t, float v) {
                throw new UnsupportedOperationException();
            }
            @Override public void close() {}
        };
    }
}
