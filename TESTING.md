# QA Testing Guide

A practical checklist for validating Tessera builds before release. Pair
this with `CLAUDE.md` (architecture) and `docs/face-rendering.md` (rendering
internals) when investigating failures.

## 1. Prerequisites

- **JDK 21+** on PATH (any JDK ≥21 can run the built jar; building requires
  JDK 25 toolchain — Gradle will provision it).
- **Gradle 9.x** if `gradlew` isn't present in your checkout. Run
  `gradle wrapper` once to generate the launch scripts, or invoke `gradle`
  directly.
- **MineSkin API key** for runtime-bake testing. Free tier works but uploads
  are slow — use a paid key for full-coverage runs. Without a key you can
  still test the bundled `heads-<N>.json` block set.
- **Paper 1.21.4** test server. `gradle runServer` provisions one in `run/`
  with the plugin already installed; no manual setup needed.
- A second account or a friend for multi-player tests (some interaction
  paths are per-player — the BARRIER swap in `progress.clientHideRealBlock`
  is one).

## 2. Build verification

Run before any in-game testing on a fresh checkout / new commit.

| Step | Command | Expected |
|------|---------|----------|
| Clean build | `gradle build` | Green. Produces `build/libs/tessera-<version>.jar`. |
| Shaded jar only | `gradle shadowJar` | Jar present, contains relocated `org.inventivetalent.tessera.shaded.{mineskin,gson}` packages. |
| Unit tests | `gradle test` | Green (suite is currently empty — failure means the harness itself broke). |
| Bake pipeline (offline) | `gradle tesseraBake` | Exits 0, processes every line in `bake-blocks.txt`, writes `src/main/resources/heads-4.json`. No `MINESKIN_API_KEY` needed for this dry run. |
| Bake pipeline (uploads) | `MINESKIN_API_KEY=… gradle tesseraBake` | Same as above; `heads-4.json` populated with real skin URLs/signatures. Re-running is a no-op. |
| Alternate grid size | `MINESKIN_API_KEY=… gradle tesseraBake -PgridN=8` | Writes `heads-8.json` alongside `heads-4.json`. Both files coexist. |

If `gradle build` fails on a clean checkout, **stop** and file before any
further QA — environment issue or a regression in CI-relevant code.

## 3. Plugin smoke test

On a fresh `gradle runServer` (or after dropping the jar into a real Paper
1.21.4 server):

1. Server starts cleanly. Check the log for a `Tessera` enable line and
   no stack traces.
2. `plugins/Tessera/config.yml` is created with defaults.
3. `/tessera` (as OP) prints help. Non-OP players get "no permission".
4. `/tessera test stone` near a placed block — at default settings stone
   is in the bundled `heads-4.json`, so the lattice should spawn instantly
   with no MineSkin upload.
5. Break a stone block by hand — animation plays, drop is correct.
6. `/tessera reload` — no error, config snapshot swaps in.

Failing any of the above = blocker.

## 4. Functional test matrix

Cover at least one block from each row × column. Use `/tessera test
<material> static` for visual inspection without animation; use `/tessera
debug debugtex on` then re-test to verify face orientation with the
red-top / green-right / blue-bottom / yellow-left markers (re-bake
required — auto-invalidates registry).

### 4.1 Block coverage

| Category | Sample blocks | What to verify |
|----------|---------------|----------------|
| Uniform full cubes | `stone`, `dirt`, `cobblestone` | Lattice fills cube; all faces look the same. |
| Non-uniform full cubes | `oak_log`, `crafting_table`, `furnace`, `pumpkin` | Top/bottom textures differ from sides; **east/west are not swapped** (regression magnet — see `HeadSkinPacker.headFaceToFaceDir` javadoc). |
| Orientable blocks | `oak_log` (axis), `furnace` (facing), `observer` (facing+powered), stairs, slabs | Variant rotation pipeline applies; e.g. an `axis=x` log has bark on N/S faces and rings on E/W. |
| State-only variants | lit `furnace`, powered `redstone_lamp`, `observer` powered | Renders the canonical (unlit) texture, **correctly oriented**. Wrong texture is *expected* (see `TODO.md`); wrong orientation is a bug. |
| Grayscale source PNGs | `stone`, `smooth_stone` | Brightness matches vanilla. If the lattice looks ~46% too bright, the grayscale colour-space fix in `ModelResolver.normalizeToArgb` regressed (CLAUDE.md §1 has the full story). |
| Tinted blocks | `grass_block`, `oak_leaves`, `vine`, `water` | With `enableTintedBlocks: true` and an API key: bake on demand, biome tint applied. With it `false`: vanilla particle fallback. |
| Disabled materials | `water`, `lava`, `fire`, `soul_fire` | Vanilla animation only; never spawn a lattice. |
| Non-full-cube | torches, fences, doors, slabs partway, glass panes | Currently fall back to vanilla animation (no lattice spawn). |
| Runtime-only blocks | anything not in `bake-blocks.txt` | First break with a valid MineSkin key triggers async bake; lattice spawns once upload completes. Re-break is instant from registry cache. |

### 4.2 Config matrix

For each row, confirm both correctness and lack of console errors. Run on
a block that exercises non-uniform faces (e.g. `oak_log axis=x`).

All options are editable in-game: `/tessera config <key> <value>` writes
to `config.yml` and reloads the snapshot in one step — no server restart
needed for most settings. Run `/tessera config` for the full key list, or
`/tessera config <key>` to read the current value. `chunkGridSize` and
`transport` are editable but require a server restart to take full effect;
the command will warn you when you set those.

| Setting | Values to test | What changes |
|---------|---------------|--------------|
| `chunkGridSize` | 1, 2, 4, 8, 16 | Lattice density. `gridN=1` is a single cube; `gridN=16` should still complete but uploads many skins on first encounter. Each size uses its own `heads-<N>.json`. |
| `transport` | `packet`, `bukkit` | Both should render identically. `bukkit` spawns real entities (visible via `/minecraft:data get entity @e`); `packet` does not. |
| `animation.mode` | `progress`, `post-break` | `progress` reacts to mining progress live; `post-break` triggers after the block is gone and respects `durationMs`. |
| `animation.style` | `shrink`, `pop` | `shrink` smooth-scales chunks; `pop` keeps full size then disappears. |
| `animation.fillInterior` | `false`, `true` | With `true`, no see-through holes mid-animation; entity count rises (significant at gridN ≥ 8). |
| `progress.clientHideRealBlock` | `false`, `true` | `true`: real block becomes BARRIER for the breaker only; in survival the plugin must still produce drops via `Player.breakBlock`. Verify drops + XP + tool durability + enchantment effects (Fortune, Silk Touch) all behave normally. |
| `progress.smoothInterpolation` | `false`, `true` | `true`: smooth lerp between vanilla's 10 destroy stages. `false`: visibly stepped. |
| `interaction.startOnLeftClick` | `false`, `true` | `true`: lattice appears on first click. Verify watchdog reverses it if you don't follow through with mining (see `leftClickGraceMs`). |
| `interaction.eagerPreload` | `false`, `true` | `true`: lattice pre-spawns when aiming. Watch entity count when looking at many blocks rapidly. |
| `interaction.minBreakDurationMs` | `0`, `500`, `1500` | Below threshold → vanilla animation only. Test with bare-hands dirt (fast) vs stone-with-pickaxe (medium) vs obsidian (slow). |
| `limits.maxConcurrentFakeBlocks` | `1`, `8`, `64` | Excess concurrent breaks fall back to vanilla without errors. Spam-break a row of blocks to verify. |
| `materials.enabled` / `disabled` | mix | Disabled materials always vanilla; enabled list `["*"]` plus disabled overrides. |
| `mineskin.apiKey` | empty, valid | Empty: only bundled blocks animate; others fall back. Invalid: log warning, fall back, no crash. |
| `metrics` | `false`, `true` | `false` should suppress bStats traffic. |
| `debug` | `false`, `true` | `true` produces verbose logs for asset/skin/progress paths. No `WARN`/`ERROR` should appear under normal use. |

### 4.3 Interaction & gameplay

- **Survival mining** with a tool that matches the block — drops, XP, and
  durability all correct. Compare to vanilla on a control server if unsure.
- **Survival mining** with a wrong tool (no drop expected) — animation
  plays, no drops, no XP.
- **Creative instamine** — falls through `durationMs` path; lattice should
  briefly appear and clean up (or be skipped if `minBreakDurationMs` filters
  it).
- **Cancelled break** (release click before completion) — lattice reverses
  out cleanly; no orphan entities.
- **Player switches target mid-break** — first lattice cleans up, second
  starts fresh.
- **Multiple players breaking adjacent blocks** — each player's breaker
  state is independent; BARRIER swap (if enabled) only affects the breaker.
- **TNT / explosions / pistons / WorldEdit** breaking blocks — these don't
  fire `BlockBreakEvent` from a player; verify Tessera does NOT animate
  them and does not error.
- **Enchantments**: Efficiency, Haste, Mining Fatigue, Fortune, Silk Touch,
  Aqua Affinity (mining underwater) — all behave per vanilla.

### 4.4 Edge cases / soak

- **Bedrock / unbreakable** blocks in survival — no animation, no error.
- **Light-emitting blocks** during animation — no light glitches.
- **Server reload mid-animation** (`/reload confirm`) — orphan entities are
  cleaned up; `/tessera reload` should be preferred but full reload must
  not leak.
- **Disable plugin while a lattice is alive** — entities are removed; no
  errors on shutdown.
- **Long soak** (30 min of continuous mining) — entity count returns to 0
  between breaks; no leak. Use `/minecraft:data get entity @e[type=item_display]`
  or the entity-count F3 line on a vanilla client.
- **Chunk unload while a lattice is alive** — entities clean up; no
  "ticking entity" errors.
- **Player logs out mid-break** — lattice cleans up.
- **High latency / packet loss** (use `tc qdisc add … netem`) — animation
  degrades gracefully.

## 5. Debug command checklist

Each debug command should be a no-op on a freshly started server. Run
through them once per release to catch regressions in the developer
surface (these aren't load-bearing for end users but break first when the
listener/factory wiring shifts).

- `/tessera test <material>` and `/tessera test <material> static`
- `/tessera config` — lists all editable keys. `/tessera config <key>`
  shows the current value; `/tessera config <key> <value>` saves to
  `config.yml` and reloads. Verify that flipping `animation.style`
  between `shrink` and `pop` and re-breaking a block reflects the new
  style with no restart.
- `/tessera debug status` — prints current rotation/flip overrides; should
  match the `DEFAULTS` in source after a clean start.
- `/tessera debug debugtex on` then `/tessera test stone` — markers
  visible. `off` reverts and re-bakes.
- `/tessera debug rebake <material>` — drops the registry entry; next
  test re-uploads (verify with `debug: true` in config).
- `/tessera debug grid <material>` — pure geometry preview spawns; no
  skin uploads.
- `/tessera debug permutations head <facedir>` — sweep displays side-by-
  side, no errors.
- `/tessera debug tilerot|tileflip|sourcerot|sourceflip <args>` — apply,
  re-test, restore via `status` then defaults. Each of these must
  invalidate the registry (next test re-uploads); `headrot` must NOT
  (runtime-only override).

## 6. Performance sanity

Not a full benchmark, but flag obvious regressions:

- `gridN=4`, `maxConcurrentFakeBlocks=8`, single player breaking dirt at
  full mining speed: no perceptible TPS dip on a stock Paper server.
- `gridN=8`, `fillInterior=true`: TPS dip should be small but measurable;
  log should show entity counts within `maxConcurrentFakeBlocks * gridN^3`.
- `gridN=16`: only meaningful with a paid MineSkin plan; entity counts in
  the thousands per block. Document any TPS impact in the bug report
  rather than failing the build over it.

Use `/spark profiler` if available to capture a flame graph when
investigating any regression.

## 7. Reporting issues

Include in every bug report:

- Tessera version (`/version Tessera`) and Paper build (`/version`).
- The relevant `config.yml` (or just the diff from defaults).
- The exact block ID + blockstate (for orientable blocks: `axis`,
  `facing`, etc.).
- A screenshot or short clip if visual.
- Server log with `debug: true` enabled around the failure.
- Whether it reproduces with `transport: bukkit` (rules out packet path).
- Whether it reproduces with `chunkGridSize: 1` (rules out tiling).
- Whether it reproduces after `/tessera debug rebake <material>` (rules
  out a stale `heads-<N>.json` entry).

## 8. Release sign-off

Don't ship a build that fails any of:

- §2 build verification all green on CI (`.github/workflows/build.yml`).
- §3 smoke test passes on a vanilla Paper 1.21.4 server.
- §4.1 covers at least: one uniform, one non-uniform, one orientable, one
  grayscale, one tinted (if `enableTintedBlocks` default is `true`).
- §4.2 default config + one non-default `chunkGridSize` (`8`).
- §4.3 survival mining with drops/XP/durability verified.
- §4.4 soak test 30 min, entity count returns to 0.
- No new `WARN`/`ERROR` log lines under normal play.
