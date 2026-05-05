package org.inventivetalent.tessera.effect;

import org.inventivetalent.tessera.core.ChunkRef;
import org.inventivetalent.tessera.core.FakeBlock;
import org.bukkit.util.Vector;
import org.inventivetalent.tessera.effect.builtin.DirectionalShrinkEffect;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Pure helpers for the directional shrink wave: how far along the wave each
 * chunk sits (a function of breaker eye direction only), and how shrunk a
 * chunk is at a given global progress (a function of that wave position +
 * the global progress + the wave window width).
 *
 * <p>Split out from {@link DirectionalShrinkEffect}
 * so the same math can be sampled at arbitrary progress values for the
 * progress-driven animation mode, in addition to the time-based wave that
 * the post-break mode uses.
 */
public final class ChunkWaveSampler {

    private ChunkWaveSampler() {}

    /**
     * For each chunk in {@code fakeBlock.chunks()}, compute its wave position
     * {@code t ∈ [0,1]}: 0 = near-side (collapses first), 1 = far-side
     * (collapses last). Uses the dot product of the chunk's local center
     * (relative to block center) onto the breaker's eye direction, then
     * normalizes across the chunk set.
     *
     * <p>{@code localCenter()} is in the unrotated grid; for variant-rotated
     * blocks (axis=x logs, facing=east furnaces) we rotate {@code rel} by
     * the block's L matrix so the wave follows the visible cube, not the
     * underlying grid. Identity rotation makes the transform a no-op.
     */
    public static double[] precomputeT(FakeBlock fakeBlock, Vector eyeDir) {
        Vector e = eyeDir.clone().normalize();
        Vector3f dir = new Vector3f((float) e.getX(), (float) e.getY(), (float) e.getZ());

        List<ChunkRef> chunks = fakeBlock.chunks();
        Quaternionf blockRot = fakeBlock.blockRotation();
        Vector3f blockCenter = new Vector3f(0.5f, 0.5f, 0.5f);
        double[] proj = new double[chunks.size()];
        if (proj.length == 0) return proj;

        double minP = Double.POSITIVE_INFINITY;
        double maxP = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < chunks.size(); i++) {
            Vector3f rel = chunks.get(i).localCenter().sub(blockCenter);
            blockRot.transform(rel);
            double p = rel.dot(dir);
            proj[i] = p;
            if (p < minP) minP = p;
            if (p > maxP) maxP = p;
        }
        double range = Math.max(1e-6, maxP - minP);
        double[] t = new double[proj.length];
        for (int i = 0; i < proj.length; i++) {
            t[i] = (proj[i] - minP) / range;
        }
        return t;
    }

    /**
     * Fraction-shrunk for a single chunk at wave position {@code t} given
     * global mining progress {@code p}. Returns 0 (untouched) at p=0 and
     * 1 (fully collapsed) at p=1. {@code window} is the wave width: at
     * intermediate p, only chunks whose t lies in the wave's active band
     * are mid-shrink; chunks ahead of the front are still 0, chunks behind
     * are already 1.
     *
     * <p>Formula: {@code s = clamp((p - t*(1 - window)) / window, 0, 1)}.
     * - At t = 0 (near side), s reaches 1 when p = window.
     * - At t = 1 (far side), s reaches 1 only when p = 1.
     * - Window 1.0 = uniform shrink (all chunks collapse together).
     */
    public static double shrunkFraction(double t, double progress, double window) {
        double w = Math.max(1e-6, Math.min(1.0, window));
        double s = (progress - t * (1.0 - w)) / w;
        if (s < 0.0) return 0.0;
        if (s > 1.0) return 1.0;
        return s;
    }
}
