package io.tessera.assemble;

import io.tessera.core.ChunkCoord;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Pure math for positioning chunk ItemDisplays inside a sub-divided block.
 * Adapted from Mosaikin's {@code WallGeometry}.
 *
 * <p><b>Bukkit's transform composition:</b>
 * {@code worldVertex = entityLocation + T + L * S * R * v}.
 * Critically, {@code T} is a plain offset — it is <em>not</em> rotated by
 * either rotation. So for chunks to land on rotated grid positions (e.g.
 * for a v3 oriented block) we have to bake the rotation into {@code T}
 * directly.
 *
 * <p>For v1 (axis-aligned full cubes) the {@code blockRotation} is the
 * identity, but the math is written generically so v3 oriented blocks plug
 * in unchanged.
 *
 * <p>Composition order: {@code R} is applied to the vertex first in
 * {@code L*S*R*v}. The face rotation therefore sits in {@code R} (inner) so
 * it picks the visible cube side in head-local space; the block rotation
 * sits in {@code L} (outer) so it rotates the already-faced cube as a whole.
 * Swapping them re-orients the cube in world axes post-block-rotation,
 * producing visibly wrong faces on tilted blocks.
 *
 * <p><b>CUBE_CENTER_PRE = (0, -0.25, 0)</b> is the empirically-measured
 * geometric center of the player-head cube relative to an ItemDisplay's
 * entity origin when {@link org.bukkit.entity.ItemDisplay.ItemDisplayTransform#NONE}
 * is used with scale 1.0 and an identity {@code Transformation}. Vanilla
 * {@code SkullBlockRenderer} applies {@code translate(0.5, 0, 0.5)} +
 * {@code scale(-1, -1, 1)} around the head model (centered at model-local
 * (0, 0.25, 0)), putting the cube center at (0.5, -0.25, 0.5) in
 * block-space. ItemDisplay.NONE then applies a partial
 * {@code translate(-0.5, 0, -0.5)} (X/Z only — unlike held-item rendering
 * which also shifts Y by -0.5), landing the cube center at (0, -0.25, 0)
 * in entity-local space. We compensate by subtracting
 * {@code L*S*R*CUBE_CENTER_PRE} from translation so the cube center lands
 * exactly on the chunk's grid cell regardless of which face is showing.
 *
 * <p>Without this compensation, adjacent chunks showing different faces
 * produce visible "staircase" gaps where the cubes don't meet flush.
 */
public final class BlockGeometry {

    /**
     * Mutable for empirical tuning via {@code /tessera debug center}.
     * Read on the main thread only.
     */
    private static Vector3f CUBE_CENTER_PRE = new Vector3f(0f, -0.25f, 0f);

    public static final Vector3f CUBE_CENTER_PRE_DEFAULT = new Vector3f(0f, -0.25f, 0f);

    public static Vector3f cubeCenterPre() {
        return new Vector3f(CUBE_CENTER_PRE);
    }

    public static void cubeCenterPre(Vector3f v) {
        CUBE_CENTER_PRE = new Vector3f(v);
    }

    private final int gridN;
    private final Quaternionf blockRotation;

    public BlockGeometry(int gridN, Quaternionf blockRotation) {
        if (gridN < 1) throw new IllegalArgumentException("gridN must be ≥ 1");
        this.gridN = gridN;
        this.blockRotation = new Quaternionf(blockRotation);
    }

    /** Identity-rotation full-cube geometry. */
    public static BlockGeometry axisAligned(int gridN) {
        return new BlockGeometry(gridN, new Quaternionf());
    }

    public int gridN() {
        return gridN;
    }

    /**
     * Uniform scale for one chunk: a player-head ItemDisplay renders an
     * 8-model-unit cube which is 0.5 blocks at scale 1.0. A chunk needs
     * {@code 1/gridN} blocks per axis, so scale = {@code (1/gridN) / 0.5
     * = 2/gridN}. For gridN=4, scale = 0.5.
     */
    public float chunkScale() {
        return 2f / gridN;
    }

    /**
     * Center of chunk {@code (cx, cy, cz)} in block-local space, where the
     * block occupies [0,1]³. The chunk grid is N cells per axis; cell
     * {@code i} spans {@code [i/N, (i+1)/N]} so its center is
     * {@code (i + 0.5) / N}.
     */
    public Vector3f chunkLocalCenter(ChunkCoord c) {
        float n = gridN;
        return new Vector3f(
                (c.x() + 0.5f) / n,
                (c.y() + 0.5f) / n,
                (c.z() + 0.5f) / n);
    }

    /**
     * Translation offset from the block origin (block's lower NW-down corner
     * in world space) to put on a chunk ItemDisplay's {@link
     * org.bukkit.util.Transformation#getTranslation Transformation.translation}
     * so the head-cube's geometric center lands exactly at the chunk's grid
     * center, regardless of which face is rotated to be visible.
     *
     * <p>{@code rotatedCell = blockRotation * (chunkCenter - blockCenter)}
     * — bake block orientation into T because Bukkit doesn't rotate T.
     * <br>{@code disp = blockRotation * (CUBE_CENTER_PRE * scale rotated by faceRot)}
     * — compensates for the head item's intrinsic offset.
     * <br>{@code T = blockCenter + rotatedCell - disp}.
     */
    public Vector3f translationFor(ChunkCoord coord, Quaternionf faceRot) {
        return translationFor(coord, faceRot, chunkScale());
    }

    /**
     * As {@link #translationFor(ChunkCoord, Quaternionf)} but with an
     * explicit {@code overrideScale} for callers that render at a non-default
     * size. The {@code disp} compensation depends on the rendered cube size,
     * so passing a scale that doesn't match the entity's transform leaves a
     * residual {@code (overrideScale - chunkScale) * CUBE_CENTER_PRE} offset
     * — visible as the lattice slightly clipping through one face of the
     * real block when the shell is rendered compressed.
     */
    public Vector3f translationFor(ChunkCoord coord, Quaternionf faceRot, float overrideScale) {
        Vector3f blockCenter = new Vector3f(0.5f, 0.5f, 0.5f);
        Vector3f cellLocal = chunkLocalCenter(coord).sub(blockCenter);
        Vector3f rotatedCell = new Quaternionf(blockRotation).transform(cellLocal);
        Vector3f disp = faceRot.transform(new Vector3f(CUBE_CENTER_PRE));
        disp.mul(overrideScale, overrideScale, overrideScale);
        new Quaternionf(blockRotation).transform(disp);
        return blockCenter.add(rotatedCell).sub(disp);
    }

    public Quaternionf blockRotation() {
        return new Quaternionf(blockRotation);
    }
}
