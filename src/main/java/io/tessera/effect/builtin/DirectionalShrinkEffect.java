package io.tessera.effect.builtin;

import io.tessera.core.ChunkRef;
import io.tessera.core.FakeBlock;
import io.tessera.effect.ChunkEffect;
import io.tessera.effect.EffectContext;
import org.bukkit.Bukkit;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shrinks every chunk to scale 0 in a wave that travels in the breaker's
 * look direction — chunks closest to the breaker shrink first, far ones
 * last. The effect lasts {@link EffectContext#durationMs} total; each
 * chunk's individual shrink takes {@link #PER_CHUNK_INTERP_MS}.
 *
 * <p>Implementation note: the shrink uses Display interpolation, not
 * per-tick server work — we set {@code interpolationDuration} and a new
 * scale-0 transformation; the client lerps to it. Total server cost per
 * effect = N scheduled tasks (one per chunk to kick off the interpolation
 * at its wave-arrival tick) + 1 cleanup task.
 */
public final class DirectionalShrinkEffect implements ChunkEffect {

    private static final int PER_CHUNK_INTERP_MS = 150;
    private static final int MS_PER_TICK = 50;

    @Override
    public void apply(FakeBlock fakeBlock, EffectContext ctx) {
        Vector dir = ctx.breakerEyeDir();
        Vector3f dirJoml = new Vector3f((float) dir.getX(), (float) dir.getY(), (float) dir.getZ());

        List<ChunkRef> chunks = new ArrayList<>(fakeBlock.chunks());
        if (chunks.isEmpty()) {
            fakeBlock.despawn();
            return;
        }

        // Project each chunk's local center onto the breaker's view direction.
        // Smaller projection = closer to camera = shrink earlier in the wave.
        Vector3f blockCenter = new Vector3f(0.5f, 0.5f, 0.5f);
        double[] projections = new double[chunks.size()];
        double minProj = Double.POSITIVE_INFINITY;
        double maxProj = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < chunks.size(); i++) {
            Vector3f rel = chunks.get(i).localCenter().sub(blockCenter);
            double p = rel.dot(dirJoml);
            projections[i] = p;
            if (p < minProj) minProj = p;
            if (p > maxProj) maxProj = p;
        }
        double range = Math.max(1e-6, maxProj - minProj);

        int interpTicks = Math.max(1, PER_CHUNK_INTERP_MS / MS_PER_TICK);
        int totalTicks  = Math.max(interpTicks + 1, ctx.durationMs() / MS_PER_TICK);
        int waveTicks   = totalTicks - interpTicks;

        for (int i = 0; i < chunks.size(); i++) {
            ChunkRef chunk = chunks.get(i);
            // closer to camera (smaller proj) → smaller t → earlier delay
            double t = (projections[i] - minProj) / range;
            // Invert so chunks at the breaker's near side go first.
            // (proj ~= dot(rel, eyeDir); positive dot = chunk on far side of block.)
            int delayTicks = (int) Math.round((1.0 - t) * waveTicks);

            Bukkit.getScheduler().runTaskLater(ctx.plugin(), () -> shrinkChunk(chunk, interpTicks),
                    Math.max(0, delayTicks));
        }

        // Cleanup at end of animation. +2 ticks of slack so the last shrink
        // finishes before we remove entities (avoids a perceptible pop).
        Bukkit.getScheduler().runTaskLater(ctx.plugin(), fakeBlock::despawn,
                totalTicks + 2L);
    }

    private static void shrinkChunk(ChunkRef chunk, int interpTicks) {
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

    @SuppressWarnings("unused")
    private static Quaternionf identityQuat() { return new Quaternionf(); }
}
