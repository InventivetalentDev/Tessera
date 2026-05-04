# Debug Commands

These subcommands of `/tessera debug` exist for plugin development and
texture tuning. They mutate static rotation / flip state, invalidate the
bake registry, or spawn diagnostic geometry. Server admins typically
won't need them; they're documented here for completeness.

All commands require the [`tessera.command`](/permissions) permission
(default: `op`). There is currently no separate node for debug
subcommands.

## Conventions

- `<face>` — a `HeadFace` slot: `TOP`, `BOTTOM`, `FRONT`, `BACK`, `RIGHT`, `LEFT`.
- `<facedir>` — a world-axis `FaceDir`: `UP`, `DOWN`, `NORTH`, `SOUTH`, `EAST`, `WEST`.
- `<material>` — a Bukkit material id, with or without the `minecraft:` prefix.

The two scales of orientation knob:

| Knob              | Scope          | When applied | Re-bake required |
| ----------------- | -------------- | ------------ | ---------------- |
| `headrot`         | per `HeadFace` | runtime spin | no               |
| `tilerot` / `tileflip`     | per `HeadFace` | bake-time    | yes (auto-invalidates) |
| `sourcerot` / `sourceflip` | per `FaceDir`  | bake-time    | yes (auto-invalidates) |

Bake-time tuners (`tilerot`, `tileflip`, `sourcerot`, `sourceflip`,
`debugtex`) automatically invalidate the registry, so the next
`/tessera test` triggers a fresh upload to MineSkin. Runtime tuners
(`headrot`) take effect on the next spawn without re-baking.

## `/tessera debug status`

Print every active rotation / flip / center override, with the current
value alongside its default. A quick sanity check before tuning.

## `/tessera debug grid [material]`

Spawn a `BlockDisplay` lattice of the requested material (defaults to
`DIAMOND_ORE`) at the looked-at cell. No skins or bakes required —
purely a geometry preview useful for verifying `BlockGeometry.CUBE_CENTER_PRE`.
Auto-removed after 30 seconds.

## `/tessera debug debugtex on|off|toggle`

Replace every chunk tile with a directional marker (face-color fill
plus colored borders: red top, green right, blue bottom, yellow left).
The single most useful tool for diagnosing orientation issues.
Bake-time mode: enabling/disabling clears the registry so the next
`/tessera test` re-bakes with markers.

## `/tessera debug rebake [material]`

Drop registry entries so the next `/tessera test` re-uploads.

- With no argument: invalidates everything.
- With a material: invalidates that one block id.

## `/tessera debug headrot <face> <0|90|180|270>`

Runtime spin around the visible face's outward axis. Per-`HeadFace`,
no re-bake required. Use this to figure out whether textures are off
because of cube rotation (in which case `headrot` fixes it) versus
texture orientation (in which case `tilerot` is the right tool).

`/tessera debug headrot reset [face]` resets one face or, with no
face, all of them.

## `/tessera debug tilerot <face> <0|90|180|270>`

In-plane rotation of each chunk's tile within its head slot.
Per-`HeadFace`, bake-time. Auto-invalidates the registry.

`/tessera debug tilerot reset [face]` resets one face or all.

## `/tessera debug tileflip <face> <none|h|v|hv>`

Mirror each chunk's tile within its head slot. Per-`HeadFace`,
bake-time. Auto-invalidates the registry.

`/tessera debug tileflip reset [face]` resets one face or all.

## `/tessera debug sourcerot <facedir> <0|90|180|270>`

Rotate the entire block-face source texture before splitting.
Per-`FaceDir`, bake-time. Use this when an entire face appears
rotated as a unit (versus `tilerot`, which rotates individual
chunks). Auto-invalidates the registry.

`/tessera debug sourcerot reset [facedir]` resets one face or all.

## `/tessera debug sourceflip <facedir> <none|h|v|hv>`

Mirror the entire block-face source texture before splitting.
Per-`FaceDir`, bake-time. Combine with `sourcerot` to express any of
the eight dihedral orientations of a square. Auto-invalidates the
registry.

`/tessera debug sourceflip reset [facedir]` resets one face or all.

## `/tessera debug face <face> <xDeg> <yDeg> <zDeg>`

Override the Euler triple in `FaceRotations` for one `HeadFace`.
Used by the canonical face-rotation pipeline; reach for this only
when fine-tuning new texture conventions, otherwise `tilerot` /
`sourcerot` are the right knobs.

`/tessera debug face reset [face]` resets one face or all.

## `/tessera debug center <x> <y> <z>`

Override `BlockGeometry.CUBE_CENTER_PRE`, the pre-rotation pivot
that all chunks share. Useful when cubes don't meet flush in a
`debug grid` preview.

`/tessera debug center reset` returns to the default vector.

## `/tessera debug permutations <head|tile|source|all> <facedir> [material]`

Sweep parameter combinations side by side, spawning a static
`FakeBlock` per combo with a vanilla compare block beneath each.
Cost depends on the kind:

| Kind     | Combinations | Re-bakes |
| -------- | ------------ | -------- |
| `head`   | 4            | 0 (instant) |
| `tile`   | 4            | 4        |
| `source` | 16           | 16       |
| `all`    | 64           | 64       |

Run on a stone block first to sanity-check.

## `/tessera debug dumppng <material>`

Split + paint the block locally and write one PNG per chunk to
`plugins/Tessera/dump-<material>/<x>-<y>-<z>.png`. Bypasses MineSkin
entirely, so you can inspect exactly what's painted into each slot
without uploading anything.
