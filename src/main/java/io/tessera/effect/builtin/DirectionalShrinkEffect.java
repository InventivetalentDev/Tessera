package io.tessera.effect.builtin;

import io.tessera.core.ChunkRef;
import io.tessera.core.FakeBlock;
import io.tessera.effect.ChunkEffect;
import io.tessera.effect.ChunkWaveSampler;
import io.tessera.effect.EffectContext;
import org.bukkit.Bukkit;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shrinks every chunk to scale 0 in a wave that travels in the breaker's
 * look direction — chunks closest to the breaker shrink first, far ones
 * last. Two entry points:
 *
 * <ul>
 *   <li>{@link #applyTimed(FakeBlock, EffectContext)} — schedules per-chunk
 *       interpolations on a fixed timeline. Used after a vanilla
 *       BlockBreakEvent (post-break mode and the instamine fallback).</li>
 *   <li>{@link #applyAtProgress} — applies a single snapshot of the wave at
 *       a given mining progress. Used by the progress-driven mode, which
 *       calls this on every {@code BlockBreakProgressUpdateEvent}.</li>
 * </ul>
 *
 * <p>Both paths share the per-chunk wave-position math via
 * {@link ChunkWaveSampler}.
 *
 * <p>Implementation note: shrinks use Display interpolation, not per-tick
 * server work — we set {@code interpolationDuration} and a new scale
 * transformation; the client lerps to it.
 */
public final class DirectionalShrinkEffect implements ChunkEffect {

    private static final int PER_CHUNK_INTERP_MS = 150;
    private static final int MS_PER_TICK = 50;

    @Override
    public void applyTimed(FakeBlock fakeBlock, EffectContext ctx) {
        List<ChunkRef> chunks = fakeBlock.chunks();
        if (chunks.isEmpty()) {
            fakeBlock.despawn();
            return;
        }

        double[] t = ChunkWaveSampler.precomputeT(fakeBlock, ctx.breakerEyeDir());

        int interpTicks = Math.max(1, PER_CHUNK_INTERP_MS / MS_PER_TICK);
        int totalTicks  = Math.max(interpTicks + 1, ctx.durationMs() / MS_PER_TICK);
        int waveTicks   = totalTicks - interpTicks;

        for (int i = 0; i < chunks.size(); i++) {
            ChunkRef chunk = chunks.get(i);
            // proj > 0 = chunk on far side of block (in same direction as
            // the player's look vector). proj < 0 = near side. We want
            // near-side chunks (small t) to shrink FIRST (small delay), far
            // ones LAST.
            int delayTicks = (int) Math.round(t[i] * waveTicks);

            Bukkit.getScheduler().runTaskLater(ctx.plugin(),
                    () -> shrinkChunkToZero(chunk, interpTicks),
                    Math.max(0, delayTicks));
        }

        // Cleanup at end of animation. +2 ticks of slack so the last shrink
        // finishes before we remove entities (avoids a perceptible pop).
        Bukkit.getScheduler().runTaskLater(ctx.plugin(), fakeBlock::despawn,
                totalTicks + 2L);
    }

    /**
     * Apply a single wave-snapshot at the given mining {@code progress} (0..1).
     * Each chunk is scaled to {@code base * (1 - shrunkFraction(t, progress, window))},
     * where {@code base} is its initial spawn scale (taken from {@code baseScales[i]}).
     * Chunks whose scale changes by less than {@code minScaleDelta} from
     * {@code prevScales[i]} are skipped to avoid redundant interpolation packets;
     * applied scales are written back into {@code prevScales} for the next call.
     *
     * <p>Does NOT despawn the FakeBlock — caller owns lifecycle.
     */
    public static void applyAtProgress(FakeBlock fakeBlock,
                                       double[] chunkT,
                                       float[] baseScales,
                                       float[] prevScales,
                                       double progress,
                                       double window,
                                       int interpTicks,
                                       double minScaleDelta) {
        List<ChunkRef> chunks = fakeBlock.chunks();
        int n = chunks.size();
        if (n == 0) return;
        int interp = Math.max(0, interpTicks);
        for (int i = 0; i < n; i++) {
            ChunkRef chunk = chunks.get(i);
            if (chunk.display().isDead()) continue;
            double s = ChunkWaveSampler.shrunkFraction(chunkT[i], progress, window);
            float target = (float) (baseScales[i] * (1.0 - s));
            if (Math.abs(target - prevScales[i]) < minScaleDelta) continue;
            prevScales[i] = target;
            Transformation cur = chunk.display().getTransformation();
            Transformation next = new Transformation(
                    cur.getTranslation(),
                    cur.getLeftRotation(),
                    new Vector3f(target, target, target),
                    cur.getRightRotation());
            chunk.display().setInterpolationDelay(0);
            chunk.display().setInterpolationDuration(interp);
            chunk.display().setTransformation(next);
        }
    }

    /** Capture the per-chunk uniform spawn scales (the X component, since spawns are uniform). */
    public static float[] captureBaseScales(FakeBlock fakeBlock) {
        List<ChunkRef> chunks = fakeBlock.chunks();
        float[] out = new float[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            out[i] = chunks.get(i).display().getTransformation().getScale().x;
        }
        return out;
    }

    private static void shrinkChunkToZero(ChunkRef chunk, int interpTicks) {
        if (chunk.display().isDead()) return;
        Transformation cur = chunk.display().getTransformation();
        Transformation zero = new Transformation(
                cur.getTranslation(),
                cur.getLeftRotation(),
                new Vector3f(0f, 0f, 0f),
                cur.getRightRotation());
        chunk.display().setInterpolationDelay(0);
        chunk.display().setInterpolationDuration(interpTicks);
        chunk.display().setTransformation(zero);
    }

    /** Sort chunks by Manhattan distance from the surface — diagnostic aid for tests. */
    static List<ChunkRef> sortBySurface(List<ChunkRef> chunks) {
        List<ChunkRef> out = new ArrayList<>(chunks);
        out.sort(Comparator.comparingInt(ChunkRef::manhattanFromSurface));
        return out;
    }
}
