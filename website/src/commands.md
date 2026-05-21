# Commands

All commands are subcommands of `/tessera`. The bake-time and runtime
tuning commands used during texture development live on the separate
[Debug Commands](/debug-commands) page.

## Permissions

Tessera registers a single permission node:

| Node              | Default | Gates                                                  |
| ----------------- | ------- | ------------------------------------------------------ |
| `tessera.command` | `op`    | The entire `/tessera` command (all subcommands, including `debug`). |

There is currently no finer-grained split between user and debug
subcommands — anyone with `tessera.command` can run every subcommand,
including the bake-time tuners that invalidate the registry. Restrict
the node to operators or a tightly-scoped admin group accordingly.

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

## `/tessera archives list | download <id>` *(paid version only)*

Browse and install pre-baked block archives served by the Tessera
backend. Requires a valid LemonSqueezy license key in `license.key` —
see [Paid version](/paid-mode) for the why.

Without a license key configured the command does nothing.

- `list` — fetch the current archive index. Each entry shows the id, name,
  `gridN`, target MC version, and size. Use the id with `download` to
  install it.
- `download <id>` — download the chosen `.ztsra` archive into
  `plugins/Tessera/heads/`, then call the addon-pack loader to make the
  pack live without a restart. The download is refused if the archive's
  `gridN` doesn't match your server's `chunkGridSize` — switch
  `chunkGridSize` and `/tessera reload` first if you want a different
  density.

Downloads are content-addressed and idempotent — running `download <id>`
twice yields the same file with the same SHA-256.
