# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Tessera is a PaperMC plugin (api-version 1.21, targets MC 1.21.4) that replaces the
vanilla block-break animation by spawning a lattice of player-head `ItemDisplay`
entities ("FakeBlock") whose textures are baked from the block's actual textures
and uploaded to MineSkin. The `gridN` config (default 4) controls the lattice
density: a 4×4×4 cube = 64 chunks per block (56 visible). Plugin id is `Tessera`,
gradle root project is `tessera`, package root is `org.inventivetalent.tessera`.

## Build / Run / Bake

The `gradle/wrapper/` jar + properties **are** committed (gradle 9.3.0 pinned
in `gradle-wrapper.properties`), but the `gradlew` / `gradlew.bat` launch
scripts are gitignored. Either run `gradle wrapper` once locally to drop the
scripts, or use a system `gradle` (9.x — `paperweight.userdev` 2.0.0-beta.21
requires Gradle 9) — invoking `gradle <task>` works in both cases. On this
dev machine neither the wrapper scripts nor a system `gradle` are on PATH —
use `! gradle <task>` in the Claude Code prompt to run tasks in-session, or
trigger builds from IntelliJ. For compile-error checking without a build,
`mcp__ide__getDiagnostics` reads the live IDE analysis. CI
(`.github/workflows/build.yml`) uses `gradle/actions/setup-gradle@v4` with
the pinned `gradle-version: '9.3.0'` and runs `gradle build --no-daemon` on
JDK 21. Java toolchain is **JDK 25** but `release` is set to **21** for
bytecode, so any JDK ≥21 can consume the artifact.

- `gradle build` — compile + run the (currently empty) test suite + produce the
  shaded plugin jar in `build/libs/tessera-<version>.jar`.
- `gradle shadowJar` — build the runnable plugin jar without running tests.
- `gradle runServer` — `xyz.jpenilla.run-paper` task; spins up a 1.21.4 Paper
  test server with the plugin loaded against `run/`.
- `gradle test` — JUnit 5 (`junit-jupiter`). There are no tests in `src/test`
  yet; if you add one, use Jupiter and don't pull in JUnit 4. Run a single
  test class with `gradle test --tests <fqcn>`.
- `MINESKIN_API_KEY=… gradle tesseraBake` — runs `org.inventivetalent.tessera.skin.bake.BakeMain`
  off-server. Reads `bake-blocks.txt`, writes
  `src/main/resources/heads-<gridN>.ztsra` (defaults to `heads-4.ztsra`;
  pass `-PgridN=<N>` to bake a different size). Internally bakes into a
  scratch `build/tessera-cache/heads-<gridN>/` folder store and zips
  it into the resource. Caches downloaded vanilla assets + intermediate
  PNGs in `build/tessera-cache/`. Idempotent — the scratch folder survives
  across runs so re-baking the same inputs only re-zips. Can run *without*
  `MINESKIN_API_KEY` to verify the asset → split → pack → assemble pipeline,
  but skins won't be uploaded. Multiple grid sizes coexist as separate files.
- `gradle tesseraConvertHeads -PgridN=4` — one-shot helper that converts a
  legacy `src/main/resources/heads-<N>.json` into the new
  `heads-<N>.ztsra` format. Exists for the v1 → v2 migration; runtime
  `plugins/Tessera/cache/heads-<N>.json` caches are migrated automatically
  on first boot of the new plugin. Pass `-PdeleteInput=true` to remove the
  source JSON after a successful conversion.

`bake-blocks.txt` is the curated v1 fixture list of block IDs to pre-bake at
build time so a freshly installed plugin handles those blocks with no network
calls. Anything else is baked on demand at runtime via `BlockBaker` (requires
the server admin to set `mineskinApiKey` in `config.yml`).

Shadow relocates `org.mineskin` → `org.inventivetalent.tessera.shaded.mineskin`,
`com.google.gson` → `org.inventivetalent.tessera.shaded.gson`, and
`com.github.benmanes.caffeine` → `org.inventivetalent.tessera.shaded.caffeine`.
JOML is bundled by Paper at runtime, declared `implementation` only so
`tesseraBake` (which doesn't load Paper) can find it on the classpath.

## Architecture

Tessera has two largely independent halves wired together at startup by
`org.inventivetalent.tessera.plugin.TesseraPlugin`:

### 1. Bake pipeline (offline → MineSkin)

Block ID → MineSkin texture. Same code runs in two contexts:

- **Build-time** via `BakeMain` (gradle `tesseraBake`), output is the
  bundled `heads-<gridN>.ztsra` resource (a zip of the folder store, see
  below).
- **Runtime** via `BlockBaker`, output is registered into the live
  `HeadsRegistry` and persisted in the writable folder store at
  `plugins/Tessera/cache/heads-<gridN>/` (alongside the PNG-hash dedup
  cache `cache/skins.json`).

Storage layout (`skin.store.TsraFormat`): one binary file per payload.
Catalogs share the same on-disk layout in two shapes — folder
(read-write, runtime) and zip (read-only). `LayeredHeadsStore` stacks
three priorities, falling through on miss and unioning `listBlocks`:

1. `plugins/Tessera/cache/heads-<N>/` — writable runtime store. Holds
   runtime bakes; receives every write.
2. `plugins/Tessera/heads/*.ztsra` — admin-supplied addon packs
   (`AddonPackLoader` scans this dir at startup, filtering by manifest
   `gridN` against `chunkGridSize`). Read-only. Designed for shipping
   a "lite" jar plus larger packs as separate downloads — addons merge
   additively with the bundled set and shadow it when both contain the
   same block.
3. `/heads-<N>.ztsra` jar resource — what the plugin ships with.
   Read-only and treated as the floor of the stack.

Files inside any catalog:

- `manifest.tsra` — declares `gridN` and producer.
- `blocks/<encoded-bake-key>.tsra` — for each block, a multi-shape index
  (one entry per distinct model the blockstate references) plus a variant
  → (shape, rotation) lookup table. A plain cube has one shape; stairs
  have three (straight, inner-corner, outer-corner); etc. Each shape
  carries its sparse `(ChunkCoord → (hash, outwardMask))` map. Tinted
  bakes use the encoded `BakeKey.toString()` form (e.g.
  `minecraft__grass_block--7fbf2e.tsra`).
- `skins/<hash[0..2]>/<hash>.tsra` — content-addressed skin payload
  (MineSkin `value`/`signature`/`mineskinUuid`). Shared between blocks and
  across shapes within a block; a uniform-textured stair reuses the same
  payloads across its three shape models.

The registry only holds the chunk → skin-hash index eagerly. Skin payloads
(~2 KB each base64 blobs) load on demand from disk through a Caffeine LRU
(`skins.cacheCapacity` in `config.yml`, default 1024 entries). At startup
the registry walks every block file in the layered store but never opens
a skin file — that happens lazily the first time a player breaks a block.
The `interaction.preloadSkinsOnLook` flag (default on) warms the cache
for the block a player is aiming at so the mining hot-path is a memory
hit.

Schema versioning: each file starts with the `"TSRA"` magic, a 1-byte
format version, a 1-byte payload type (manifest / block / skin), and two
reserved bytes. Current Block format is `v2` (multi-shape + per-chunk
outward-face masks); `v1` files (single-shape, no outward masks — every
pre-multishape bake) read transparently with synthesized shape key
`"default"` and outward masks recomputed via `FaceDir.isOutwardAt` (cube
assumption, correct for every v1 bake). Writers always emit `v2`.
Readers reject unknown magic / version (treated as corruption) so a
hand-edited file surfaces rather than silently parses as empty.

One-shot JSON migration: if a server upgrades from the v1 plugin and the
runtime cache is still `cache/heads-<N>.json`, `JsonMigrator` runs on the
first boot of the new plugin, writes the equivalent `.tsra` folder, and
renames the source to `.migrated`. The same `JsonMigrator` is exposed via
`gradle tesseraConvertHeads` for the bundled resource.

Per-grid-size files mean switching `chunkGridSize` in config doesn't
discard previously uploaded skins — each size keeps its own folder.

Pipeline stages (`org.inventivetalent.tessera.skin.bake.BlockBaker.doBake` / `BakeMain.bakeOne`):

1. `assets.fetch.McAssetClient` downloads vanilla model + texture JSON/PNGs from
   `mcasset.cloud` for the requested MC version, caches under `assets/`.
2. `assets.model.ModelResolver` resolves a `BlockModel` — a multi-shape
   container, one shape per distinct model path the blockstate
   references. For full-cube models (every element is
   `from=[0,0,0] to=[16,16,16]`) the shape is a `ShapeContent.CubeFaces`
   carrying 6 face textures + tint + overlay data (the classic cube path,
   supports biome tint via `model.withTint(argb)`). For anything else —
   slabs, stairs, daylight detector, … —
   `assets.model.ShapeVoxelizer` voxelizes the `elements` array into a
   sparse cell lattice with per-cell-per-face textures sampled at the
   element's UV window; the result is a `ShapeContent.VoxelizedShape`
   that downstream consumes directly (no cube assumption, no tint
   support in v1). Stairs are the canonical multi-shape block: their
   blockstate references three models (straight / inner-corner /
   outer-corner) so the resolver runs the voxelizer three times and
   stores all three under their model paths.
   **Colour-space trap:** some vanilla textures are grayscale PNGs
   (`color_type=0`, e.g. `stone.png`, `smooth_stone.png`). Java's
   `ImageIO.read()` decodes these to `TYPE_BYTE_GRAY` whose `ColorModel`
   uses `CS_GRAY` (linear γ=1.0). Calling `getRGB()` on that image
   converts linear→sRGB, brightening mid-range grey 126 → ~182 — the
   baked skin then renders ~46% too bright. `ModelResolver.normalizeToArgb()`
   fixes this by reading raw raster samples for grayscale images, bypassing
   colour management. RGBA and indexed textures (`light_gray_concrete`,
   planks, etc.) are unaffected.
3. `split.TextureSplitter` is the per-shape dispatcher. For
   `ShapeContent.CubeFaces` it cuts each face into `gridN × gridN` 8×8
   tiles, applying per-`FaceDir` `SourceRotations` / `SourceFlips` debug
   overrides first. For `ShapeContent.VoxelizedShape` it passes the
   pre-voxelized chunk list through unchanged — the splitting work
   already happened in `ShapeVoxelizer` at resolve time.
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

`assemble.FakeBlockFactory.create(loc, bakeKey, shapeKey, blockRotation, …)`
is the entry point that turns a `BakeKey` + shape selector + world location +
per-state rotation into a `FakeBlock` (a list of `ChunkRef` = `ItemDisplay` +
local-grid metadata). One `ItemDisplay` per visible chunk; all share the
block-corner entity location so a single `Location.teleport` would move the
whole lattice (intended for v2 physics). The `blockRotation` quaternion is
applied as `leftRotation` (block-level orientation), independent of the
canonical face-rotation `rightRotation` (see invariant below).

Runtime is **shape-agnostic**: the registry stores the chunk lattice as a
sparse `Map<ChunkCoord, ChunkEntry>` per (BakeKey, shapeKey), where
`ChunkEntry` carries both the skin payload and the chunk's outward-face
mask (baked at resolve time, not recomputed). The spawn path iterates
whatever coords the registry holds for the picked shape — no cube
boundary checks, no special-casing for slabs vs stairs vs cubes.

`effect.builtin.DirectionalShrinkEffect` is the only v1 effect: it scales each
chunk to 0 with a wave delay computed from each chunk's projection on the
breaker's eye direction. Uses Display interpolation (client-side lerp), not
per-tick server work — server cost is N scheduler tasks for N chunks plus one
cleanup.

### Variant + shape selection pipeline

A blockstate variant key (e.g. `facing=east,half=top,shape=inner_left`)
binds to (a) the model file to render and (b) a runtime rotation. We bake
one shape per distinct model the blockstate references and store the
variant → (shape, rotation) lookup table in the block file.

1. **Bake time** (`assets.model.ModelResolver.parseVariants`): walks the
   blockstate JSON's `variants` map, capturing each entry's `model` path
   + `x`/`y` rotation as a `VariantEntry`. Enumerate distinct model
   paths, resolve each (cube fast path or `ShapeVoxelizer`), and store
   all shapes under their model paths in the `BlockModel.shapes` map.
   Build a `variantKey → VariantBinding(shapeKey, VariantRotation)`
   table. `BakeMain` / `BlockBaker` write all shapes + the variant table
   into the catalog's per-block `.tsra` file.
2. **Spawn time** (`plugin.BlockBreakListener.spawn`):
   `VariantKey.fromBlockData(blockData)` produces a vanilla-format key;
   `VariantKey.pickMatching` narrows it against
   `registry.variantsFor(key)` by progressively dropping nuisance
   properties (`waterlogged`, `powered`, …) until a known variant
   matches; `registry.variantBindingFor(key, matchedKey)` returns a
   `ShapeVariantBinding(shapeKey, rotation)`. The shape key picks which
   pre-baked voxelization to spawn; the rotation (`Ry(−y)·Rx(x)` —
   negated Y because vanilla's `y` rotates clockwise from above whereas
   JOML's `rotateY` is right-handed) is applied as `blockRotation` on
   the lattice. Unknown variants fall back to the default shape +
   identity rotation.

This handles **orientation and shape**. Stairs corner variants render as
actual L-shapes (different model), top-half slabs render upside-down
(different model in vanilla 1.21, or `x=180` rotation in older versions
— both work via the same pipeline). State-dependent textures (lit
furnace fronts, redstone-lamp glow) still render the canonical model's
textures, correctly oriented (see `TODO.md`).

### Critical invariant: canonical face rotation

Every `ItemDisplay` in a `FakeBlock` uses **the same** `rightRotation`:
`HeadRotations.compose(FRONT, SOUTH, FaceRotations.of(FRONT))` — which is
`Ry(180°)` cancelling the player-head ItemStack's intrinsic `Ry(180°)`. With
that cancellation, every UV slot's normal lands on its like-named world axis
(`TOP→+Y`, `BOTTOM→−Y`, `FRONT→+Z (south)`, `BACK→−Z (north)`).

**The X axis is mirrored from what the slot names suggest:** the player-head
model's `east` (+X) face samples the `LEFT` UV slot, and `west` (−X) samples
`RIGHT`. So `HeadSkinPacker.headFaceToFaceDir` maps `RIGHT→WEST` and
`LEFT→EAST`. This is invisible on uniform-textured blocks but mandatory for
non-uniform blocks like `oak_log`; see PR #4 / the javadoc on
`HeadSkinPacker.headFaceToFaceDir` for the full reasoning.

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
