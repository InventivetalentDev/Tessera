package org.inventivetalent.tessera.effect.builtin;

import org.inventivetalent.tessera.core.ChunkRef;
import org.inventivetalent.tessera.core.FakeBlock;
import org.inventivetalent.tessera.effect.ChunkEffect;
import org.inventivetalent.tessera.effect.ChunkWaveSampler;
import org.inventivetalent.tessera.effect.EffectContext;
import org.inventivetalent.tessera.plugin.TesseraConfig.CollapseStyle;
import org.inventivetalent.tessera.transport.DisplayHandle;
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
            int delayTicks = (int) Math.round(t[i] * waveTicks);

            Bukkit.getScheduler().runTaskLater(ctx.plugin(),
                    () -> collapseChunk(chunk, interpTicks),
                    Math.max(0, delayTicks));
        }

        Bukkit.getScheduler().runTaskLater(ctx.plugin(), fakeBlock::despawn,
                totalTicks + 2L);
    }

    /**
     * Apply a single wave-snapshot at the given mining {@code progress} (0..1).
     * Chunks whose scale changes by less than {@code minScaleDelta} from
     * {@code prevScales[i]} are skipped to avoid redundant packets.
     * Applied scales are written back into {@code prevScales} for the next call.
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
            DisplayHandle handle = chunk.handle();
            if (!handle.isAlive()) continue;
            double s = ChunkWaveSampler.shrunkFraction(chunkT[i], progress, window);
            float target;
            if (style == CollapseStyle.POP) {
                target = s >= 1.0 ? 0f : baseScales[i];
            } else {
                target = (float) (baseScales[i] * (1.0 - s));
            }
            if (Math.abs(target - prevScales[i]) < minScaleDelta) continue;
            prevScales[i] = target;
            Transformation cur = handle.getTransformation();
            Transformation next = new Transformation(
                    cur.getTranslation(),
                    cur.getLeftRotation(),
                    new Vector3f(target, target, target),
                    cur.getRightRotation());
            handle.setTransformation(next, 0, style == CollapseStyle.POP ? 0 : interp);
        }
    }

    /**
     * Multiply every chunk's scale (and the per-chunk {@code baseScales} /
     * {@code prevScales} that drive {@link #applyAtProgress}) by
     * {@code factor}. Used in two directions, both timed to a simultaneous
     * sendBlockChange so the snap is masked by the block-vs-BARRIER swap.
     */
    public static void rescaleShell(FakeBlock fakeBlock,
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
            DisplayHandle handle = chunk.handle();
            if (!handle.isAlive()) continue;
            Transformation cur = handle.getTransformation();
            Transformation next = new Transformation(
                    cur.getTranslation(),
                    cur.getLeftRotation(),
                    new Vector3f(newScale, newScale, newScale),
                    cur.getRightRotation());
            handle.setTransformation(next, 0, interp);
        }
    }

    /** Capture the per-chunk uniform spawn scales (the X component, since spawns are uniform). */
    public static float[] captureBaseScales(FakeBlock fakeBlock) {
        List<ChunkRef> chunks = fakeBlock.chunks();
        float[] out = new float[chunks.size()];
        for (int i = 0; i < chunks.size(); i++) {
            out[i] = chunks.get(i).handle().getTransformation().getScale().x;
        }
        return out;
    }

    private void collapseChunk(ChunkRef chunk, int interpTicks) {
        DisplayHandle handle = chunk.handle();
        if (!handle.isAlive()) return;
        Transformation cur = handle.getTransformation();
        Transformation zero = new Transformation(
                cur.getTranslation(),
                cur.getLeftRotation(),
                new Vector3f(0f, 0f, 0f),
                cur.getRightRotation());
        handle.setTransformation(zero, 0, style == CollapseStyle.POP ? 0 : interpTicks);
    }

    /**
     * Despawn chunks that the wave has fully passed and that have already
     * animated to (near-)zero scale. Guards against false-despawn of
     * freshly-spawned chunks whose scale hasn't risen yet.
     */
    public static void despawnPassedChunks(FakeBlock fakeBlock,
                                           double[] chunkT,
                                           float[] baseScales,
                                           float[] currentScales,
                                           double progress,
                                           double window,
                                           float minDelta) {
        List<ChunkRef> chunks = fakeBlock.chunks();
        double rear = progress - window;
        for (int i = 0; i < chunks.size(); i++) {
            DisplayHandle handle = chunks.get(i).handle();
            if (!handle.isAlive()) continue;
            if (baseScales[i] <= 0f) continue;     // not yet spawned (pre-allocated slot)
            if (currentScales[i] >= minDelta) continue; // still visible
            if (chunkT[i] > rear) continue;         // wave hasn't fully passed
            handle.despawn();
        }
    }

    /** Sort chunks by Manhattan distance from the surface — diagnostic aid for tests. */
    static List<ChunkRef> sortBySurface(List<ChunkRef> chunks) {
        List<ChunkRef> out = new ArrayList<>(chunks);
        out.sort(Comparator.comparingInt(ChunkRef::manhattanFromSurface));
        return out;
    }
}
