package io.tessera.effect.builtin;

import io.tessera.core.ChunkRef;
import io.tessera.core.FakeBlock;
import io.tessera.effect.ChunkEffect;
import io.tessera.effect.ChunkWaveSampler;
import io.tessera.effect.EffectContext;
import io.tessera.plugin.TesseraConfig.CollapseStyle;
import org.bukkit.Bukkit;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Directional break effect with two collapse styles:
 * <ul>
 *   <li>{@link CollapseStyle#SHRINK} — chunks scale uniformly toward 0 as
 *       the wave passes through them.</li>
 *   <li>{@link CollapseStyle#POP} — chunks stay at full size until the wave
 *       hits them, then disappear in one tick.</li>
 * </ul>
 * Both share the same wave-position math via {@link ChunkWaveSampler}; the
 * only difference is what each chunk's per-tick transformation looks like.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #applyTimed(FakeBlock, EffectContext)} — schedules per-chunk
 *       transformations on a fixed timeline. Used after a vanilla
 *       BlockBreakEvent (post-break mode and the instamine fallback).</li>
 *   <li>{@link #applyAtProgress} — applies a single snapshot of the wave at
 *       a given mining progress. Used by the progress-driven mode, which
 *       calls this on every {@code BlockBreakProgressUpdateEvent}.</li>
 * </ul>
 *
 * <p>Implementation note: shrinks use Display interpolation, not per-tick
 * server work — we set {@code interpolationDuration} and a new scale
 * transformation; the client lerps to it.
 */
public final class DirectionalShrinkEffect implements ChunkEffect {

    private static final int PER_CHUNK_INTERP_MS = 150;
    private static final int MS_PER_TICK = 50;

    private final CollapseStyle style;

    /** Default constructor preserves the legacy SHRINK behaviour. */
    public DirectionalShrinkEffect() {
        this(CollapseStyle.SHRINK);
    }

    public DirectionalShrinkEffect(CollapseStyle style) {
        this.style = style;
    }

    @Override
    public void applyTimed(FakeBlock fakeBlock, EffectContext ctx) {
        List<ChunkRef> chunks = fakeBlock.chunks();
        if (chunks.isEmpty()) {
            fakeBlock.despawn();
            return;
        }

        double[] t = ChunkWaveSampler.precomputeT(fakeBlock, ctx.breakerEyeDir());

        int interpTicks = style == CollapseStyle.POP
                ? 0
                : Math.max(1, PER_CHUNK_INTERP_MS / MS_PER_TICK);
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
                    () -> collapseChunk(chunk, interpTicks),
                    Math.max(0, delayTicks));
        }

        // Cleanup at end of animation. +2 ticks of slack so the last shrink
        // finishes before we remove entities (avoids a perceptible pop).
        Bukkit.getScheduler().runTaskLater(ctx.plugin(), fakeBlock::despawn,
                totalTicks + 2L);
    }

    /**
     * Apply a single wave-snapshot at the given mining {@code progress} (0..1).
     * For SHRINK each chunk is scaled to {@code base * (1 - shrunkFraction(t,
     * progress, window))}. For POP each chunk is full-size while the wave is
     * in front of it (s &lt; 1) and 0 once the wave has passed (s == 1).
     * Chunks whose scale changes by less than {@code minScaleDelta} from
     * {@code prevScales[i]} are skipped to avoid redundant interpolation
     * packets; applied scales are written back into {@code prevScales} for
     * the next call.
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
                                       double minScaleDelta,
                                       CollapseStyle style) {
        List<ChunkRef> chunks = fakeBlock.chunks();
        int n = chunks.size();
        if (n == 0) return;
        int interp = Math.max(0, interpTicks);
        for (int i = 0; i < n; i++) {
            ChunkRef chunk = chunks.get(i);
            if (chunk.display().isDead()) continue;
            double s = ChunkWaveSampler.shrunkFraction(chunkT[i], progress, window);
            float target = (style == CollapseStyle.POP)
                    ? (s >= 1.0 ? 0f : baseScales[i])
                    : (float) (baseScales[i] * (1.0 - s));
            if (Math.abs(target - prevScales[i]) < minScaleDelta) continue;
            prevScales[i] = target;
            Transformation cur = chunk.display().getTransformation();
            Transformation next = new Transformation(
                    cur.getTranslation(),
                    cur.getLeftRotation(),
                    new Vector3f(target, target, target),
                    cur.getRightRotation());
            chunk.display().setInterpolationDelay(0);
            // POP wants an instant transition (interp=0); for SHRINK we honour
            // the caller's pacing.
            chunk.display().setInterpolationDuration(style == CollapseStyle.POP ? 0 : interp);
            chunk.display().setTransformation(next);
        }
    }

    /**
     * Reverse the {@code INITIAL_SHELL_COMPRESSION} applied at spawn so the
     * lattice grows from "fits inside the real block" to "fills the block
     * volume" exactly when the BARRIER block change is sent. {@code factor}
     * is the inverse compression (e.g. {@code 1f / 0.99f} ≈ 1.0101 to undo
     * a 1% contraction). Multiplies {@code baseScales} and {@code prevScales}
     * in place so subsequent {@link #applyAtProgress} calls compute targets
     * off the new (uncompressed) baseline. {@code interpTicks} controls
     * how the client lerps to the new size; pass 0 for an instant snap that
     * lines up with the barrier swap.
     */
    public static void expandShellToFullScale(FakeBlock fakeBlock,
                                              float[] baseScales,
                                              float[] prevScales,
                                              float factor,
                                              int interpTicks) {
        List<ChunkRef> chunks = fakeBlock.chunks();
        int n = chunks.size();
        int interp = Math.max(0, interpTicks);
        for (int i = 0; i < n; i++) {
            ChunkRef chunk = chunks.get(i);
            baseScales[i] *= factor;
            float newScale = prevScales[i] * factor;
            prevScales[i] = newScale;
            if (chunk.display().isDead()) continue;
            Transformation cur = chunk.display().getTransformation();
            Transformation next = new Transformation(
                    cur.getTranslation(),
                    cur.getLeftRotation(),
                    new Vector3f(newScale, newScale, newScale),
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

    private void collapseChunk(ChunkRef chunk, int interpTicks) {
        if (chunk.display().isDead()) return;
        Transformation cur = chunk.display().getTransformation();
        Transformation zero = new Transformation(
                cur.getTranslation(),
                cur.getLeftRotation(),
                new Vector3f(0f, 0f, 0f),
                cur.getRightRotation());
        chunk.display().setInterpolationDelay(0);
        chunk.display().setInterpolationDuration(style == CollapseStyle.POP ? 0 : interpTicks);
        chunk.display().setTransformation(zero);
    }

    /** Sort chunks by Manhattan distance from the surface — diagnostic aid for tests. */
    static List<ChunkRef> sortBySurface(List<ChunkRef> chunks) {
        List<ChunkRef> out = new ArrayList<>(chunks);
        out.sort(Comparator.comparingInt(ChunkRef::manhattanFromSurface));
        return out;
    }
}
