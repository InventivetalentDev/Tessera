package org.inventivetalent.tessera.api;

import org.inventivetalent.tessera.core.ChunkCoord;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Everything needed to spawn one correctly-oriented chunk of a block as a
 * player-head {@code ItemDisplay}, without Tessera spawning anything itself.
 *
 * <p>Drop the values straight into a Bukkit {@code Transformation}:
 * <pre>{@code
 * new Transformation(layout.translation(), layout.leftRotation(),
 *     new Vector3f(layout.scale()), layout.rightRotation());
 * }</pre>
 * with the display's entity location at the block's lower-NW-down corner
 * (i.e. {@code Location} of the block, integer-floored). Bukkit composes the
 * head vertices as {@code worldVertex = entityLocation + T + L·S·R·v}, so the
 * {@code rightRotation} is the canonical face-rotation Tessera bakes against
 * (the same for every chunk) and {@code leftRotation} is the per-blockstate
 * variant orientation. The translation already compensates for the head item's
 * intrinsic offset so adjacent chunks meet flush — do not recompute it.
 *
 * <p>Build the head item from {@link #skin()} (a {@code GameProfile} texture
 * property). All vectors/quaternions are fresh, owned copies — safe to mutate.
 *
 * @param coord         grid position of this chunk in the N×N×N lattice
 * @param localCenter   chunk center in block-local space ([0,1]³), pre-rotation
 * @param translation   {@code Transformation.translation}
 * @param leftRotation  {@code Transformation.leftRotation} (variant orientation)
 * @param rightRotation {@code Transformation.rightRotation} (canonical face rotation)
 * @param scale         uniform scale per axis ({@code 2/gridN})
 * @param skin          the texture payload for this chunk
 */
public record ChunkLayout(ChunkCoord coord, Vector3f localCenter, Vector3f translation,
                          Quaternionf leftRotation, Quaternionf rightRotation, float scale,
                          SkinPayload skin) {
}
