package org.inventivetalent.tessera.api;

import org.bukkit.block.data.BlockData;
import org.inventivetalent.tessera.core.BakeKey;
import org.inventivetalent.tessera.core.BlockKey;
import org.inventivetalent.tessera.core.ChunkCoord;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only access to Tessera's pre-baked block data for third-party plugins.
 *
 * <p>Tessera bakes each block into an N×N×N lattice of player-head skins (one
 * skin per visible chunk) and uploads them to MineSkin. This interface lets
 * other plugins reuse that data — query what's available, pull the per-chunk
 * texture payloads, get pre-oriented per-chunk {@linkplain ChunkLayout layout}
 * to spawn their own {@code ItemDisplay}s, and trigger an on-demand bake for a
 * block that hasn't been baked yet.
 *
 * <p><b>Getting an instance.</b> Tessera registers the implementation with
 * Bukkit's {@code ServicesManager} at enable. Use {@link Tessera#api()}, or:
 * <pre>{@code
 * TesseraApi api = Bukkit.getServicesManager().load(TesseraApi.class);
 * }</pre>
 * Declare {@code softdepend: [Tessera]} (or {@code depend}) in your plugin.yml
 * so Tessera enables first.
 *
 * <p><b>Threading.</b> Query methods ({@link #isAvailable}, {@link #heads},
 * {@link #layout}) are safe to call from any thread. {@link #requestBake}
 * returns a {@link CompletableFuture} completed off the main thread; hop back to
 * the main thread before touching Bukkit state in its callbacks.
 *
 * <p>The grid density ({@link #gridN}) is a server-wide config value; build your
 * own geometry against it rather than assuming a fixed size.
 */
public interface TesseraApi {

    /** Current API contract version. Bumped on incompatible changes. */
    int VERSION = 1;

    /** @return {@link #VERSION} of the running implementation. */
    int apiVersion();

    /** @return the configured grid density N (an N×N×N lattice per block). */
    int gridN();

    /** @return true if {@code block} is baked and ready (bundled, addon, or runtime). */
    boolean isAvailable(BlockKey block);

    /** @return a snapshot of every block currently available (across all tints). */
    Set<BlockKey> availableBlocks();

    /**
     * The raw per-chunk skin payloads for {@code key}.
     *
     * @return chunk → payload, or an empty map if the block isn't available.
     *         Loads payloads from disk on first access (cached thereafter).
     */
    Map<ChunkCoord, SkinPayload> heads(BakeKey key);

    /**
     * Per-chunk {@linkplain ChunkLayout layout} for {@code block} oriented to a
     * placed blockstate. Resolves the blockstate's variant rotation (log axis,
     * facing, …) and applies it, so logs/stairs/etc. come out correctly turned.
     *
     * @param block the block type
     * @param state the placed {@link BlockData} (use {@code block.createBlockData()}
     *              for the default orientation)
     * @return one entry per visible chunk, or an empty list if not available.
     */
    List<ChunkLayout> layout(BlockKey block, BlockData state);

    /**
     * Per-chunk {@linkplain ChunkLayout layout} with an explicit block-level
     * rotation. Pass {@code new Quaternionf()} (identity) for the canonical
     * orientation. Prefer {@link #layout(BlockKey, BlockData)} unless you're
     * driving the rotation yourself.
     *
     * @return one entry per visible chunk, or an empty list if not available.
     */
    List<ChunkLayout> layout(BakeKey key, Quaternionf blockRotation);

    /**
     * Bake {@code block} on demand if it isn't already available. Resolves
     * immediately to {@link BakeOutcome#SUCCESS} if already present. Routes to
     * MineSkin via the server's API key or license; returns
     * {@link BakeOutcome#NOT_CONFIGURED} if the server has neither.
     *
     * <p>The future completes off the main thread.
     */
    CompletableFuture<BakeOutcome> requestBake(BlockKey block);

    /**
     * Whether this server can bake blocks it doesn't already have — i.e. it has
     * a MineSkin API key or a valid license. A diagnostic for deciding whether
     * to offer the full block palette or only {@link #availableBlocks()}. When
     * false, {@link #requestBake} for an absent block yields
     * {@link BakeOutcome#NOT_CONFIGURED}.
     */
    boolean canBakeNewBlocks();
}
