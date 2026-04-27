# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Tessera is a PaperMC plugin (api-version 1.21, targets MC 1.21.4) that replaces the
vanilla block-break animation by spawning a lattice of player-head `ItemDisplay`
entities ("FakeBlock") whose textures are baked from the block's actual textures
and uploaded to MineSkin. The `gridN` config (default 4) controls the lattice
density: a 4×4×4 cube = 64 chunks per block (56 visible). Plugin id is `Tessera`,
gradle root project is `tessera`, package root is `io.tessera`.

## Build / Run / Bake

The repo intentionally does **not** vendor a Gradle wrapper (see `.gitignore`).
Use a system `gradle` (any 8.x that supports the `paperweight.userdev` plugin).
Java toolchain is **JDK 25** but `release` is set to **21** for bytecode.

- `gradle build` — compile + run the (currently empty) test suite + produce the
  shaded plugin jar in `build/libs/tessera-<version>.jar`.
- `gradle shadowJar` — build the runnable plugin jar without running tests.
- `gradle runServer` — `xyz.jpenilla.run-paper` task; spins up a 1.21.4 Paper
  test server with the plugin loaded against `run/`.
- `gradle test` — JUnit 5 (`junit-jupiter`). There are no tests in `src/test`
  yet; if you add one, use Jupiter and don't pull in JUnit 4. Run a single
  test class with `gradle test --tests <fqcn>`.
- `MINESKIN_API_KEY=… gradle tesseraBake` — runs `io.tessera.skin.bake.BakeMain`
  off-server. Reads `bake-blocks.txt`, writes `src/main/resources/heads.json`,
  caches downloaded vanilla assets + intermediate PNGs in `build/tessera-cache/`.
  Idempotent — re-running on the same input is a no-op once `heads.json` is
  populated. Can run *without* `MINESKIN_API_KEY` to verify the asset →
  split → pack → assemble pipeline, but skins won't be uploaded.

`bake-blocks.txt` is the curated v1 fixture list of block IDs to pre-bake at
build time so a freshly installed plugin handles those blocks with no network
calls. Anything else is baked on demand at runtime via `BlockBaker` (requires
the server admin to set `mineskinApiKey` in `config.yml`).

Shadow relocates `org.mineskin` → `io.tessera.shaded.mineskin` and
`com.google.gson` → `io.tessera.shaded.gson`. JOML is bundled by Paper at
runtime, declared `implementation` only so `tesseraBake` (which doesn't load
Paper) can find it on the classpath.

## Architecture

Tessera has two largely independent halves wired together at startup by
`io.tessera.plugin.TesseraPlugin`:

### 1. Bake pipeline (offline → MineSkin)

Block ID → MineSkin texture. Same code runs in two contexts:

- **Build-time** via `BakeMain` (gradle `tesseraBake`), output is bundled
  `heads.json`.
- **Runtime** via `BlockBaker`, output is registered into the live
  `HeadsRegistry` and persisted in `plugins/Tessera/cache/skins.json`.

Pipeline stages (`io.tessera.skin.bake.BlockBaker.doBake` / `BakeMain.bakeOne`):

1. `assets.fetch.McAssetClient` downloads vanilla model + texture JSON/PNGs from
   `mcasset.cloud` for the requested MC version, caches under `assets/`.
2. `assets.model.ModelResolver` resolves a `BlockModel` (texture refs +
   "tinted" flag). Tinted blocks (grass, leaves) are unsupported in v1.
3. `split.TextureSplitter` cuts each block face's texture into
   `gridN × gridN` 8×8 tiles, applying any per-`FaceDir` `SourceRotations` /
   `SourceFlips` debug overrides first.
4. `skin.HeadSkinPacker` builds one 64×64 `HeadSkin` per *unique* chunk by
   hashing its 6-tile bundle. A uniform stone block at gridN=4 collapses to **3**
   skin uploads (face-center / edge / corner) regardless of total chunk count.
   Non-outward `HeadFace` slots get a deterministic filler so the dedup hash is
   stable.
5. `skin.SkinAssembler` paints the six tiles into the player-head 64×64 UV
   layout (with U-flip on BOTTOM to cancel vanilla's UV convention) and writes
   the PNG. Per-`HeadFace` `TileRotations`/`TileFlips` apply here.
6. `skin.SkinUploader` uploads to MineSkin via `org.mineskin:java-client`,
   throttled and deduped by `SkinDiskCache` (keyed by post-paint PNG SHA-256, so
   the same image hits cache regardless of how it was built).

### 2. Render pipeline (BlockBreakEvent → ItemDisplay lattice)

`plugin.BlockBreakListener` (priority `MONITOR`) intercepts breaks, gates on
`config.yml`'s enable/disable lists and `maxConcurrentFakeBlocks`, then either
spawns immediately (registry hit) or kicks off async runtime baking and spawns
post-bake.

`assemble.FakeBlockFactory.create` is the entry point that turns a `BlockKey` +
world location into a `FakeBlock` (a list of `ChunkRef` = `ItemDisplay` +
local-grid metadata). One `ItemDisplay` per visible chunk; all share the
block-corner entity location so a single `Location.teleport` would move the
whole lattice (intended for v2 physics).

`effect.builtin.DirectionalShrinkEffect` is the only v1 effect: it scales each
chunk to 0 with a wave delay computed from each chunk's projection on the
breaker's eye direction. Uses Display interpolation (client-side lerp), not
per-tick server work — server cost is N scheduler tasks for N chunks plus one
cleanup.

### Critical invariant: canonical face rotation

Every `ItemDisplay` in a `FakeBlock` uses **the same** `rightRotation`:
`HeadRotations.compose(FRONT, SOUTH, FaceRotations.of(FRONT))` — which is
`Ry(180°)` cancelling the player-head ItemStack's intrinsic `Ry(180°)`. With
that cancellation, every UV slot's normal lands on its like-named world axis
(`TOP→+Y`, `FRONT→+Z (south)`, `RIGHT→+X (east)`, etc.).

`HeadSkinPacker.buildFaceTiles` paints each outward `FaceDir`'s tile into its
matching `HeadFace` slot, so the canonical rotation makes every outward face
correct from every viewing angle without per-chunk rotation picking. **Do not
re-introduce per-chunk `pickPrimaryFace` logic** — it cannot work for chunks
with more than one outward face (corners and edges); see `docs/face-rendering.md`
for the full post-mortem on what was tried.

If a face's texture still looks wrong after canonical rotation, the fix is one
of:
- `skin.TileRotations` / `skin.TileFlips` — per-`HeadFace` in-plane orientation,
  bake-time.
- `split.SourceRotations` / `split.SourceFlips` — whole-face source transform
  per `FaceDir`, bake-time.
- `assemble.HeadRotations` — runtime spin around the slot's outward axis,
  no re-bake.

These are all wired to mutable static defaults so the `/tessera debug` commands
can tune them live. Once values are dialed in, fold them into the respective
`DEFAULTS` blocks in those classes.

## Debugging the render pipeline

In-game commands (perm `tessera.command`, default OP). Full surface is in the
javadoc on `plugin.TesseraCommand`; common ones:

- `/tessera test [material] [static]` — bake (if needed) + spawn a FakeBlock at
  the looked-at cell. `static` skips the shrink effect and lingers 5 minutes
  for inspection.
- `/tessera debug debugtex on` — replace tiles with directional markers
  (face-color fills + red-top / green-right / blue-bottom / yellow-left edges).
  Re-bake required (auto-invalidates registry). The single most useful tool for
  diagnosing orientation issues.
- `/tessera debug status` — dump every rotation/flip override.
- `/tessera debug rebake [material]` — drop registry entries so the next test
  re-uploads.
- `/tessera debug grid [material]` — pure geometry preview (BlockDisplays
  using the requested material) — no skins required, useful when verifying
  `BlockGeometry.CUBE_CENTER_PRE`.
- `/tessera debug permutations <head|tile|source|all> <facedir> [material]` —
  sweep parameter combinations side by side. Historical use, kept for fine-
  tuning new texture conventions.

Bake-time tuning commands (`tilerot`, `tileflip`, `sourcerot`, `sourceflip`,
`debugtex`) auto-invalidate the registry; runtime ones (`headrot`) don't.
After tuning bake-time, the next `/tessera test` triggers a fresh upload.

## Conventions

- Keep `FaceDir` (world axes: UP/DOWN/N/S/E/W) and `HeadFace` (UV slots:
  TOP/BOTTOM/FRONT/BACK/RIGHT/LEFT) cleanly separated. The single canonical
  mapping lives in `HeadSkinPacker.headFaceToFaceDir` / `faceDirToHeadFace` —
  do not duplicate it.
- All assets and skins are addressed by content hashes (`util.Hashing`); the
  registry/dedup invariant is *same hash → same MineSkin texture*. Anything that
  changes painted bytes must also change the hash, or upstream cache lookup
  must be bypassed (see `TileRotations.consumeStale` / `bypassCache` plumbing
  through `BlockBaker`).
- `HeadsRegistry` is loaded once at startup but mutable so `BlockBaker` can
  register runtime-baked blocks. Read paths use `ConcurrentHashMap`; main thread
  (listener) and async pool (baker) share it.
- Configs are immutable record snapshots (`TesseraConfig`); `/tessera reload`
  swaps the snapshot wholesale rather than mutating in place.
