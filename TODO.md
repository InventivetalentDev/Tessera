- [ ] should generate heads for blocks not in heads.json at runtime
- [ ] add command tab-complete
- [x] try using BlockBreakProgressUpdateEvent to update the break animation with that, instead of animating after the block is already broken
- [ ] blockstate-aware textures for blocks where the model itself differs by
      state (furnace lit/unlit, observer powered, redstone_lamp lit, etc.).
      The current variant-rotation pipeline handles orientation but always
      bakes the canonical-variant model — so e.g. lit furnaces render with
      the unlit front texture (correctly oriented). Needs per-state
      texture bake + state-keyed registry to fix.
- [ ] more non-cube shapes — slabs + stairs work now via the voxelizer.
      Next batches require different strategies:
      - Multi-element complex (anvil, brewing stand, lectern, cake, end
        portal frame): voxelizer handles them, just need to add to
        bake-blocks.txt and verify the output looks right.
      - Sub-cell-thin (fences, walls, panes, iron bars, buttons, levers,
        carpets, pressure plates, snow layers, chains, torches): at
        gridN=4 each cell is 4/16 wide so these geometries round to 0–1
        cells, giving unhelpful silhouettes. Either bump to a separate
        `heads-8.ztsra` for these (gridN=8, 2/16 cells) or render them
        with a different strategy (no lattice, just shrink the whole
        ItemDisplay as a single chunk).
      - Cross-plane (saplings, flowers, mushrooms, dead bushes, ferns):
        two intersecting thin planes are fundamentally not voxel-shaped.
        Probably needs a separate "billboard" effect path.
      - Connected/neighbor-dependent (walls, fences, panes, iron bars,
        glow lichen, vines, redstone wire): connection state isn't in
        the blockstate, it's derived from neighbors at render time.
        Need to read the world's neighbor blocks at spawn and pick a
        composite shape.
      - Entity-rendered (chests, beds, banners, bells, hanging signs,
        skulls, conduits, decorated pots, shulker boxes): no usable
        block model. Out of scope for the lattice-of-heads approach.
