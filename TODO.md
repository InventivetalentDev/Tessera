- [ ] should generate heads for blocks not in heads.json at runtime
- [ ] add command tab-complete
- [ ] try using BlockBreakProgressUpdateEvent to update the break animation with that, instead of animating after the block is already broken
- [ ] blockstate-aware textures for blocks where the model itself differs by
      state (furnace lit/unlit, observer powered, redstone_lamp lit, etc.).
      The current variant-rotation pipeline handles orientation but always
      bakes the canonical-variant model — so e.g. lit furnaces render with
      the unlit front texture (correctly oriented). Needs per-state
      texture bake + state-keyed registry to fix.
- [ ] BOTTOM face on directional blocks (furnace, jack o' lantern) renders
      rotated/flipped relative to vanilla. Surfaced by the parent-switch →
      element-driven rewrite (commit 432a91f), which correctly puts
      `furnace_side` on DOWN — previously the parent-switch wrongly painted
      `#top` there, hiding any UV mismatch. The DOWN splitter mapping
      `(cx, cz)` was tuned with rotation-symmetric textures (oak_log_top,
      pumpkin_top); a clearly directional texture exposes that the head
      model's BOTTOM UV may not actually share image-Y direction with TOP
      the way the prior fix assumed. Diagnostic recipe + per-face knobs
      documented in `docs/face-rendering.md` "Known issue".
