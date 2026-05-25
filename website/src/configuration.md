# Configuration

Tessera writes its defaults to `plugins/Tessera/config.yml` on first
start. Settings are reloaded with `/tessera reload` (a fresh immutable
snapshot replaces the old one — in-flight animations finish under the
old values).

## Top-level options

| Option                | Type    | Default   | Description |
| --------------------- | ------- | --------- | ----------- |
| `transport`           | enum    | `packet`  | Entity transport backend. `packet` sends raw clientbound packets directly to the breaking player only — no server-side entity tracking, best performance. `bukkit` uses the Paper `World.spawn()` API as a safer fallback if a Minecraft update renames the internal `Display` fields the packet path uses via reflection. |
| `chunkGridSize`       | integer | `4`       | How many chunks per axis each block is split into. Must divide 16 (block textures are 16×16 px) — permitted values: `1`, `2`, `4`, `8`, `16`. See the warning below. |
| `enableTintedBlocks`  | boolean | `true`    | Bake biome-tinted blocks (grass, leaves, water, vines) on demand. The resolved per-biome tint is read at break time and multiplied into the source texture, so each distinct biome tint produces a separate MineSkin upload the first time it's encountered. Disable to fall back to the vanilla particle animation for tinted blocks. |
| `metrics`             | boolean | `true`    | Send anonymous usage statistics to [bStats](https://bstats.org/plugin/bukkit/Tessera/31093). Set to `false` to opt out. |
| `debug`               | boolean | `false`   | Verbose logging for asset resolution, skin packing, and progress-mode state transitions (spawn / transfer / reverse / break / watchdog). |

::: warning chunkGridSize re-bakes everything
Changing `chunkGridSize` invalidates every previously baked block.
Bundled `heads.json` entries baked at the old gridN are dropped on
startup, and every block has to be re-uploaded to MineSkin at the new
size on demand as players break blocks. Make sure `mineskin.apiKey`
is set before raising it.

At `gridN=4`: 4×4 tiles per face, 64 chunks/block (56 visible). Each
chunk samples a 4×4 px region.

At `gridN=16`: 16×16 tiles per face, 4096 chunks/block (1352 visible).
Each chunk is one source pixel — many more entities and many more unique
skin uploads for non-uniform textures. Lower `limits.maxConcurrentFakeBlocks`
accordingly.
:::

## `mineskin`

| Option   | Type   | Default | Description |
| -------- | ------ | ------- | ----------- |
| `apiKey` | string | `""`    | API key for runtime uploads of blocks not in the bundled `heads.json`. Get one at [account.mineskin.org](https://account.mineskin.org/keys/new/?name=Tessera&intendedUse=plugin_or_mod&projectLink=https%3A%2F%2Ftessera.inventivetalent.org). Without a key, the plugin only renders blocks present in `heads.json` and falls back to vanilla particles for everything else. |

## `materials`

| Option     | Type            | Default | Description |
| ---------- | --------------- | ------- | ----------- |
| `enabled`  | list of strings | `["*"]` | Materials this plugin should animate. Use `["*"]` for all full-cube blocks. |
| `disabled` | list of strings | `["minecraft:water", "minecraft:lava", "minecraft:fire", "minecraft:soul_fire"]` | Materials to never animate, even when `enabled` includes `"*"`. |

## `limits`

| Option                     | Type    | Default | Description |
| -------------------------- | ------- | ------- | ----------- |
| `maxConcurrentFakeBlocks`  | integer | `8`     | Hard cap on concurrent FakeBlocks (sub-block effect entities). Excess block-break events fall back to the vanilla animation. |

## `animation`

| Option           | Type    | Default     | Description |
| ---------------- | ------- | ----------- | ----------- |
| `mode`           | enum    | `progress`  | When the chunked animation plays. `progress` drives the wave by live mining progress — chunks animate as the player mines, and the block disappears exactly when the wave finishes. `post-break` is the legacy behavior: wait for `BlockBreakEvent` then play the wave on the empty cell over `durationMs`. |
| `style`          | enum    | `shrink`    | How a chunk transitions out as the wave reaches it. `shrink` uniformly scales the chunk toward 0 (smooth collapse). `pop` keeps the chunk at full size until the wave hits, then disappears in one tick (crumble look). |
| `durationMs`     | integer | `600`       | Total duration of the directional break animation in milliseconds. Only used in `post-break` mode (and as the instamine fallback). |
| `waveWindow`     | float   | `0.25`      | Width of the shrink wave along the breaker's eye direction (0..1). Smaller = sharper wave front, larger = more chunks collapsing in parallel. `0.25` means roughly a quarter of the chunks are mid-shrink at any moment. |
| `fillInterior`   | boolean | `false`     | Spawn extra chunks inside the cube so the player doesn't see straight through holes in the lattice while the wave is mid-animation. Only interior chunks on the half nearest the breaker are filled — the far half stays hollow because by the time the wave reaches it the cube is mostly gone. Cost scales with `chunkGridSize`: at `gridN=4` it's `+(gridN-2)^3 = +8` entities per FakeBlock (negligible); at `gridN=8` it's `+216` per FakeBlock — lower `limits.maxConcurrentFakeBlocks` if you raise gridN with this on. |

## `progress`

Used when `animation.mode = progress`.

| Option                 | Type    | Default | Description |
| ---------------------- | ------- | ------- | ----------- |
| `clientHideRealBlock`  | boolean | `true`  | Send `sendBlockChange(BARRIER)` to the breaker so the real block doesn't z-fight with (or hide behind) the shrinking FakeBlock chunks. The real block stays on the server. See the survival caveat below. |
| `minDelta`             | float   | `0.02`  | Minimum change in progress before the transformation is re-applied. Avoids redundant interpolation packets when progress barely moved between ticks. |
| `smoothInterpolation`  | boolean | `true`  | Smooth between vanilla's 0.1 progress steps. `BlockBreakProgressUpdateEvent` only fires on destroy-stage boundaries (so 10 events for the whole break), which on its own looks choppy. With this enabled, each event sets the display interpolation duration equal to the expected gap until the next event so the client lerps continuously through the gap. |

::: info Survival caveat for clientHideRealBlock
A client mining a `BARRIER` refuses to send the final
"destroy completed" packet, so vanilla on its own never fires
`BlockBreakEvent`. The plugin compensates by force-breaking the real
block via `Player.breakBlock(...)` once the progress wave reaches 1.0 —
that fires `BlockBreakEvent` synchronously, runs enchantments, and
produces tool-correct drops just like a normal break.
:::

## `interaction`

| Option                 | Type    | Default | Description |
| ---------------------- | ------- | ------- | ----------- |
| `startOnLeftClick`     | boolean | `true`  | Start the animation on the first left-click instead of waiting for vanilla's first 0.1 progress event (~600 ms in for default mining). Worst case (the player swings without committing to mining) the watchdog reverses the speculative animation. |
| `leftClickGraceMs`     | integer | `500`   | If no `BlockBreakProgressUpdateEvent` arrives within this many ms after a speculative left-click spawn, reverse it back out. Should be roughly one destroy stage (50–700 ms) — too small and slow blocks get rolled back during the first stage; too large and idle swings linger. |
| `eagerPreload`         | boolean | `false` | Pre-spawn the chunk lattice while the player is aiming at a block so entities are already fully rendered when they left-click. When mining begins, the real block is hidden immediately (no tick delay), making the animation visible from the very first hit on fast blocks like dirt or planks-with-an-axe. The trade-off is a brief window of extra entity traffic for every block the player looks at (~1 second of extra entity lifetime per aimed-at block on average). |
| `minBreakDurationMs`   | integer | `1000`  | Minimum estimated break duration in milliseconds for the animation to play. Faster blocks fall back to the vanilla particle animation. The duration is estimated from `Block.getBreakSpeed(player)` at interaction time — it accounts for tool, enchantments, and effects but not Haste/Mining Fatigue beacons on older Paper builds. Set to `0` to always animate. Fast blocks that are only partially spawned before the break look worse than vanilla, hence the threshold. |
