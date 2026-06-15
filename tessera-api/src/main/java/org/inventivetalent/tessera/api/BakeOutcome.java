package org.inventivetalent.tessera.api;

/**
 * Result of a {@link TesseraApi#requestBake} call.
 *
 * <p>On-demand baking is the baseline for any configured Tessera install — it
 * reaches MineSkin either through a server-set MineSkin API key or through a
 * Tessera license that proxies uploads. The licensing path is invisible to
 * consumers; only {@link #NOT_CONFIGURED} signals a server that has neither and
 * therefore can serve only its pre-bundled blocks.
 */
public enum BakeOutcome {
    /** The block was baked (or already present) and is now available. */
    SUCCESS,
    /** The block can't be baked — non-cube model, unsupported tint, missing assets. */
    UNBAKEABLE,
    /**
     * The server has neither a MineSkin API key nor a valid license, so novel
     * blocks can't be baked. Pre-bundled / addon-pack blocks still work.
     */
    NOT_CONFIGURED,
    /** Baking failed unexpectedly (network, MineSkin error). Safe to retry. */
    FAILED
}
