package io.tessera.effect;

import io.tessera.core.FakeBlock;

/**
 * One way to animate a {@link FakeBlock}'s chunks. Implementations schedule
 * Bukkit tasks to update display transforms, drive trajectory tables (v2),
 * etc. Effects own the FakeBlock for the duration of the animation and are
 * responsible for calling {@link FakeBlock#despawn()} at the end.
 */
public interface ChunkEffect {

    /** Apply the effect. Must be called on the main thread. */
    void apply(FakeBlock fakeBlock, EffectContext ctx);
}
