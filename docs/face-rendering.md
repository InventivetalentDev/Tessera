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
world space, so each UV slot's normal lands on its like-named world axis:

| HeadFace slot | UV-space normal | World direction after canonical rotation | FaceDir |
|---------------|------------------|------------------------------------------|---------|
| TOP           | +Y               | +Y (up)                                  | UP      |
| BOTTOM        | −Y               | −Y (down)                                | DOWN    |
| FRONT         | +Z               | +Z (south)                               | SOUTH   |
| BACK          | −Z               | −Z (north)                               | NORTH   |
| RIGHT         | +X               | +X (east)                                | EAST    |
| LEFT          | −X               | −X (west)                                | WEST    |

`HeadSkinPacker.buildFaceTiles` already paints each outward `FaceDir`'s
tile into its matching `HeadFace` slot, so once the cube is canonically
rotated, every outward face shows the correct tile to whichever direction
that face points.

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
- **The BOTTOM pre-mirror in `SkinAssembler`** — vanilla samples the
  BOTTOM face with U flipped relative to the other five, so we paint
  BOTTOM with U swapped on `Graphics2D.drawImage` to cancel that.
  Independent of orientation strategy.
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
   `FRONT`=orange (south), `BACK`=purple (north), `RIGHT`=magenta (east),
   `LEFT`=teal (west). Same colour everywhere on the face including
   edges and corners — *not* a different colour per chunk row. If you do
   see per-row variation, the canonical rotation isn't being applied.
3. Read the four border colours on each face (red top / green right /
   blue bottom / yellow left). If a face has them rotated, set
   `TileRotations.<that-slot>` to the rotation that brings them upright.
4. If a face has them mirrored, use `TileFlips`.
5. Once tuned, fold values into the respective `DEFAULTS` blocks.

`SourceRotations` and `SourceFlips` are pre-split transforms on the
whole source texture per `FaceDir`. They're rarely needed once the
six-tile orientation is correct — they exist for textures whose source
orientation disagrees with the chunk-grid axes.
