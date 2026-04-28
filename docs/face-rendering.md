# Face rendering — design notes

How a sub-block chunk's six face-tiles get onto a player-head ItemDisplay
so that every outward face renders correctly *from every viewing angle*.

This is post-mortem documentation for the face-orientation work that
chewed through several debugging sessions. The TL;DR is: there is exactly
one cube rotation that makes every UV slot face the right world direction
at once, and we use it for every chunk. Earlier per-face rotation
attempts couldn't work in principle.

## The setup

A FakeBlock is a `gridN³` lattice of `ItemDisplay`s. Each display carries
a `PLAYER_HEAD` ItemStack with a custom skin baked from the block's
texture. The 64×64 skin canvas has six 8×8 face slots — `TOP`, `BOTTOM`,
`FRONT`, `BACK`, `LEFT`, `RIGHT` — each mapping to one cube face in the
player-head model's UV layout.

For each visible chunk we want: every outward face of the chunk's tiny
cube shows the correct slice of the parent block's texture. A face-center
chunk has 1 outward face; an edge chunk has 2; a corner chunk has 3.

## The canonical rotation (current)

Source of truth: `FakeBlockFactory.create` and `DebugGridSpawner.spawn`.

Every chunk's `ItemDisplay` gets the same `rightRotation`:

```java
HeadRotations.compose(HeadFace.FRONT, FaceDir.SOUTH, FaceRotations.of(HeadFace.FRONT));
```

`FaceRotations.FRONT` is `Ry(180°)`. That cancels the player-head
ItemStack's built-in `Ry(180°)` (documented in `FaceRotations`'s
javadoc). With the two flips cancelling, the cube renders identity in
world space. Slot-to-world mapping is then dictated by the vanilla head
model's per-face UVs (which faces sample which skin region):

| HeadFace slot | Skin UV rect    | Model cube face that samples it | World direction | FaceDir |
|---------------|-----------------|---------------------------------|-----------------|---------|
| TOP           | (8,0)-(16,8)    | up                              | +Y (up)         | UP      |
| BOTTOM        | (16,0)-(24,8)   | down                            | −Y (down)       | DOWN    |
| FRONT         | (8,8)-(16,16)   | south                           | +Z (south)      | SOUTH   |
| BACK          | (24,8)-(32,16)  | north                           | −Z (north)      | NORTH   |
| LEFT          | (16,8)-(24,16)  | east                            | +X (east)       | EAST    |
| RIGHT         | (0,8)-(8,16)    | west                            | −X (west)       | WEST    |

The X axes look swapped relative to slot-name intuition: the LEFT slot
("wearer's left cheek") renders on world *east*, and RIGHT renders on
*west*. That's because Steve's natural orientation faces south, and the
wearer's left side is at +X when facing south. The vanilla model's
`east` cube face is wired to sample the LEFT skin region, full stop.

`HeadSkinPacker.buildFaceTiles` accounts for this when packing: each
outward `FaceDir`'s tile is placed into the slot that actually renders
on that world direction (so `chunk.tile(EAST)` goes into LEFT, not
RIGHT), and once the cube is canonically rotated every outward face
shows the correct tile.

`HeadRotations` is composed in for the per-slot debug spin, but with
canonical rotation the spin is now around the FRONT slot's local +Z
axis (i.e. the south-facing face), which is mostly only useful for
fine-tuning that one face. With canonical orientation, per-face texture
issues should be addressed with `TileRotations` / `TileFlips` instead.

## What we tried that didn't work

Long story short: every previous attempt had `pickPrimaryFace` selecting
a single `FaceDir` per chunk, then applying a per-`HeadFace`
`FaceRotations` rotation. That model fundamentally cannot make all
outward faces correct at once for a chunk with more than one outward
face. The reason: `FaceRotations.X` for `X ≠ FRONT` rotates the cube,
which moves the *other* slots' normals to wrong world directions.
Concretely: `FaceRotations.RIGHT = Ry(−90°)` orients the RIGHT slot
correctly toward east, but moves the TOP slot's normal off +Y, the FRONT
slot off +Z, and so on. So an EAST+UP corner chunk would have either
`FaceRotations.RIGHT` (TOP misoriented) or `FaceRotations.TOP` (RIGHT
misoriented) — never both right.

Things that were tweaked in pursuit of a per-primary-face fix that
didn't, and couldn't, work:

- **Per-`HeadFace` `FaceRotations` table** — copied from Mosaikin and
  refined empirically. Works perfectly in Mosaikin because each Mosaikin
  head only renders one face (the `FRONT` slot of an animation frame);
  the table picks which world direction that one face points. Tessera
  needs all six faces correct simultaneously, which one rigid rotation
  per chunk can never give for chunks with >1 outward face.
- **Per-`HeadFace` `TileRotations` and `TileFlips`** — adjusts the
  in-plane orientation of a slot's texture before paint. This compensates
  for UV-axis convention mismatches between source and slot, but does
  nothing about which world direction the slot ends up pointing.
- **Per-`FaceDir` `SourceRotations` and `SourceFlips`** — rotates/flips
  the entire source texture before splitting. Same story — rearranges
  which pixels go in which slot, not which world direction the slot
  faces.
- **Per-slot `HeadRotations`** — runtime in-plane spin around the slot's
  cube-local outward axis. Same story — UV-plane only, doesn't fix world
  orientation.
- **Permutation sweep** (`/tessera debug sweep`) — spawned every
  combination of source/tile rot/flip looking for one that read correct.
  Necessarily failed: no point in the parameter space contains a fix for
  the underlying "one rotation per chunk, multiple visible faces" problem.

Symptom that finally pinned the cause: with `FaceDebugTint` enabled, the
EAST face showed teal (`LEFT` fill) and purple (`BACK` fill) tiles
leaking onto edge/corner chunks instead of magenta (`RIGHT` fill)
everywhere. That's `pickPrimaryFace` doing exactly what it was designed
to do — picking `DOWN`, `UP`, `NORTH`, `SOUTH` ahead of `EAST` for edge
chunks per the `FaceDir.values()` iteration order — and then the cube's
rotation pointing the picked slot east-ish but everything else wrong.

## What did work and got kept

- **`FaceDebugTint`** (face-colored fills + four-color edge stripes —
  red top, green right, blue bottom, yellow left) — made the bug
  diagnosable. Without it you couldn't tell which slot you were looking
  at on a uniform-textured block.
- **`HeadSkinPacker.buildFaceTiles`** painting each outward `FaceDir`'s
  tile into its matching `HeadFace` slot, with a single-tile filler for
  non-outward slots so identical chunks dedup to one MineSkin upload.
  The filler does no harm under canonical rotation — non-outward slots
  point inward and aren't visible.
- **Nearest-neighbor scaling for tile→slot upscale** — replaced an
  edge-replication padding scheme that produced a stretched-2×2-center
  artifact masking everything else.
- **`BlockGeometry.CUBE_CENTER_PRE = (0, −0.25, 0)`** — empirically
  measured offset that compensates for the player-head model's centroid
  not coinciding with the displayed cube's centroid. Without this, chunks
  drift in formation.

## Empirical tuning workflow

If a face still looks off after the canonical-rotation fix:

1. `/tessera debug debugtex on` then break + replace the block to bake
   debug markers.
2. Look at each face from outside the block. You should see one HeadFace
   fill colour per face: `TOP`=light gray, `BOTTOM`=dark gray,
   `FRONT`=orange (south), `BACK`=purple (north), `LEFT`=teal (east),
   `RIGHT`=magenta (west). The X swap is real — see the slot↔world
   direction table at the top of this doc. Same colour everywhere on the
   face including edges and corners — *not* a different colour per chunk
   row. If you do see per-row variation, the canonical rotation isn't
   being applied.
3. Read the four border colours on each face (red top / green right /
   blue bottom / yellow left). If a face has them rotated, set
   `TileRotations.<that-slot>` to the rotation that brings them upright.
4. If a face has them mirrored, use `TileFlips`.
5. Once tuned, fold values into the respective `DEFAULTS` blocks.

`SourceRotations` and `SourceFlips` are pre-split transforms on the
whole source texture per `FaceDir`. They're rarely needed once the
six-tile orientation is correct — they exist for textures whose source
orientation disagrees with the chunk-grid axes.

## Per-face texture resolution: read the model, don't switch on parent

Source of truth: `ModelResolver.pickFaceTextures` and `resolveModelChain`.

**Don't** map texture variables to `FaceDir` based on parent name.
Vanilla model JSON encodes the per-face mapping directly — every cube
parent's `elements[i].faces.<dir>.texture` is a `"#var"` reference that
resolves through the texture-variable bindings accumulated up the parent
chain. Read those refs and you're done; the mapping naturally handles
`cube`, `cube_all`, `cube_column`, `orientable`, `orientable_with_bottom`,
`cube_top`, `cube_bottom_top`, and any future cube parent.

The previous parent-switch implementation got `orientable`'s mapping
wrong: vanilla's `orientable.json` has `north: "#front"`, but the switch
hardcoded `out.put(FaceDir.SOUTH, front)`. The bug was invisible for
blocks placed `facing=east`/`west` because `Ry(±90°)` lands the carved
face on the right axis regardless of which side it started on, but
flipped `facing=north`/`south` jack o' lanterns and furnaces 180°.

**Chain-walk gotcha.** When you walk the parent chain to harvest texture
vars + `elements`, do NOT break at the first `CUBE_PARENTS` entry.
Vanilla's `elements` array lives in `block/cube` (the deepest cube
parent), but most blocks' immediate parent is something like
`cube_column` or `orientable` — both also in `CUBE_PARENTS`. Stopping
early would never reach the model that actually has elements. Walk all
the way to the root, set `terminal` on the *first* cube parent seen
(that's still the right answer for the cube-only filter upstream), and
keep going. Disk-cached, so the extra HTTP calls are free.

## Variant rotation: Minecraft Y is opposite-sign from JOML

Source of truth: `ModelResolver.VariantRotation.toQuat`.

Vanilla blockstate `y` rotates **clockwise** when viewed from above:
`y=90` maps the model's `-Z` (north) face to world `+X` (east), which is
exactly what `facing=east` expects. JOML's `rotateY` is the standard
right-hand rule (counter-clockwise from above), the opposite sense. So
`toQuat` negates `yDeg` before feeding it to `rotateY`.

`xDeg` does *not* need negation: vanilla pitch and JOML's `rotateX` are
both right-handed. Verified against `oak_log[axis=z]` (`x=90` alone),
which renders correctly with the unflipped sign.

**Why it took two bugs to surface this.** The original `toQuat` fed
`yDeg` straight in (wrong sign). The original `pickFaceTextures` for
`orientable` put `front` on south instead of north (wrong face). Those
two bugs cancelled exactly for `facing=east`/`west` (start with front on
+Z, JOML +90° → +X = east; right answer for the wrong reason), so only
`facing=north`/`south` looked broken. Axis-only blocks (logs) didn't
care because `±X` is the same axis.

After fixing `pickFaceTextures` to read from `elements`, the toQuat sign
error became the sole remaining offset and surfaced as east/west
swapped. **Order matters**: the texture fix has to come first or the
two errors keep cancelling. Lesson generalised: when two transformations
compose and one looks "right," verify each in isolation against
ground-truth before assuming the chain is correct.

## Known issue: BOTTOM face on directional blocks looks rotated/flipped

Symptom: after the parent-switch removal, furnace and jack o' lantern
bottom faces appear rotated or mirrored relative to vanilla. Furnaces
are the obvious case — vanilla's `orientable.json` has `down: "#side"`,
so the bottom now correctly shows `furnace_side` (a highly directional
texture with a chimney groove at the top). Before the fix, the
parent-switch mistakenly painted `#top` on DOWN, hiding any UV mismatch
behind a near-symmetric texture.

The DOWN splitter mapping (`TextureSplitter.sourceTile`) is currently
`(cx, cz)` — same as UP — based on empirical tuning with rotation-
symmetric textures (oak_log_top rings, pumpkin_top). With a clearly
directional texture now landing on the bottom, the head model's BOTTOM
UV convention may not actually match TOP's the way the prior fix
assumed.

**Diagnostic recipe** (to converge on the right per-face transform
without guessing):

1. `/tessera debug debugtex on`, break + replace a furnace, look at the
   bottom face from below. The four border colours (red top, green
   right, blue bottom, yellow left) tell you the slot's image-axis
   orientation directly.
2. If borders are rotated relative to upright: `/tessera debug tilerot
   bottom <90|180|270>` until they read upright.
3. If borders are mirrored: `/tessera debug tileflip bottom <h|v|hv>`.
4. If neither alone fixes it, the issue is at the source-texture level
   (image-X/Y axes don't align with the chunk-grid axes for that face);
   try `/tessera debug sourcerot down` / `sourceflip down`.
5. Once tuned, fold the value into `TileRotations.DEFAULTS` /
   `TileFlips.DEFAULTS` for `BOTTOM`, or `SourceRotations.DEFAULTS` /
   `SourceFlips.DEFAULTS` for `DOWN`. Re-test with `oak_log_top`,
   `pumpkin_top`, and stone to make sure the previously-correct cases
   stay correct.

