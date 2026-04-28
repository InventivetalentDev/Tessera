- [ ] should generate heads for blocks not in heads.json at runtime
- [ ] add command tab-complete
- [ ] try using BlockBreakProgressUpdateEvent to update the break animation with that, instead of animating after the block is already broken
- [ ] blockstate-aware textures for blocks where the model itself differs by
      state (furnace lit/unlit, observer powered, redstone_lamp lit, etc.).
      The current variant-rotation pipeline handles orientation but always
      bakes the canonical-variant model — so e.g. lit furnaces render with
      the unlit front texture (correctly oriented). Needs per-state
      texture bake + state-keyed registry to fix.
