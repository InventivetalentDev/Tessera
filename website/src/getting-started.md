# Getting Started

Tessera is a PaperMC plugin that replaces the vanilla block-break animation
with a lattice of player-head `ItemDisplay` entities. Each broken block is
split into an N×N×N grid (default 4×4×4 = 64 chunks per block, 56 visible)
that animate independently — chunks shrink or pop in a wave aligned to the
breaker's view direction, in sync with live mining progress.

A curated set of common blocks ships pre-baked into `heads.json`, so a
freshly installed plugin handles those blocks with zero network calls.
Anything outside that list is baked on demand at runtime, which requires a
free [MineSkin](https://account.mineskin.org/) API key.

## Requirements

- **Server:** PaperMC, API version `1.21` (tested against MC 1.21.4).
- **Java:** 21 or later.
- **Network:** Outbound HTTPS to `mineskin.org` is required only when baking
  blocks at runtime (i.e. for blocks not in the bundled `heads.json`).

Folia is **not** supported.

## Install

1. Place the plugin jar (`tessera-<version>.jar`) into your server's
   `plugins/` directory.
2. Start the server once. Tessera will create
   `plugins/Tessera/config.yml` with sensible defaults.
3. (Optional but recommended) Open `plugins/Tessera/config.yml`,
   set `mineskin.apiKey` to a key from
   [account.mineskin.org](https://account.mineskin.org/), and run
   `/tessera reload` (or restart the server). Without a key, Tessera
   only animates the blocks present in `heads.json` and falls back to
   the vanilla particle animation for everything else.

## First test

Once the plugin is loaded:

1. Join the server as an op (the `tessera.command`
   [permission](/commands#permissions) defaults to `op`).
2. Look at a block.
3. Run `/tessera test stone`. If `stone` is already baked you'll see a
   FakeBlock spawn instantly; otherwise Tessera will upload skins to
   MineSkin (a few seconds) and then spawn it.
4. Try `/tessera test stone static` — that variant skips the shrink
   effect and lingers for five minutes so you can walk around and inspect.

If the test command works, breaking blocks normally will now play the
chunked animation (subject to the [configuration](/configuration)).

## Next steps

- Tune `chunkGridSize`, animation timing, and the materials allowlist in
  [Configuration](/configuration).
- Browse the [Commands](/commands) reference for the available
  `/tessera` subcommands and the permission node.
