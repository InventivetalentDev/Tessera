# Commands

All commands are subcommands of `/tessera` and gated by the
[`tessera.command`](/permissions) permission (default: `op`).

This page documents the user-facing commands. The bake-time and
runtime tuning commands used during texture development live on the
separate [Debug Commands](/debug-commands) page.

## `/tessera test [material] [static]`

Bake the requested material if it isn't already in the registry, then
spawn a `FakeBlock` at the cell you're looking at.

- `material` — defaults to `stone`. With or without `minecraft:` prefix.
- `static` — optional flag. Spawns the FakeBlock without the shrink
  animation and keeps it alive for five minutes so you can walk around
  and inspect it.

If the material isn't in `heads.json` and `mineskin.apiKey` is unset,
the command refuses with a hint to configure the key first.

## `/tessera bake <material> [tint:#RRGGBB]`

Trigger a bake for `<material>` without spawning anything. Useful for
warming the registry / disk cache (for example after editing
`bake-blocks.txt` on a running server) without disturbing the world.

Reports the upload count once the splitter/packer has decided how
many MineSkin uploads are actually required, and a completion message
when the bake finishes.

- `material` — required. With or without `minecraft:` prefix.
- `tint:#RRGGBB` — optional, required for biome-tinted blocks (grass,
  leaves, water). The hex value is the resolved colour multiplier the
  runtime listener would read off a player breaking the block in the
  target biome. Without it the bake fails the same way the runtime
  listener does for tinted blocks.

If the bake key is already in the registry, the command reports it as
a no-op and points at `/tessera debug rebake` for invalidating before
re-uploading.

## `/tessera reload`

Reload `plugins/Tessera/config.yml`. The configuration is replaced as
an immutable snapshot, so existing animations finish under their old
settings and new breaks pick up the new values.
