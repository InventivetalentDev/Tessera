# Paid build

Tessera is available in two flavours:

- **Free.** Animates blocks
  in the bundled `heads-<gridN>.ztsra` resource out of the box. Anything
  else requires a [MineSkin](https://account.mineskin.org/) API key (subscription recommended)
  in `config.yml` so the plugin can bake new blocks on demand.
- **Paid.** Distributed through [BuiltByBit](https://builtbybit.com).
  Identical animation surface, but two ergonomic differences for server
  admins:
  1. **No MineSkin API key required.** Bakes route through our backend
     server, with the same MineSkin subscription benefits.
  2. **On-demand archive downloads.** The `/tessera archives` command
     lets you browse and install pre-baked archives (for example at
     larger `chunkGridSize` values) without having to bake them
     yourself.


## When to use each

- You're testing the plugin or just want full control over your
  MineSkin plan → **free version**.
  - Also recommended if you have a MineSkin subscription, since that 
    will guarantee the fastest bake times for new blocks.
- You're running a production server and want to skip the MineSkin
  signup, don't want to wait for new block-bakes, or want
  larger pre-baked grid sizes
  without baking them yourself → **paid version** on BuiltByBit.

The bundled blocks render identically in both. Performance,
configuration surface, and the rendering pipeline are unchanged.

## Installing the paid build

1. Purchase Tessera on BuiltByBit and download the jar to your machine.
   Don't redistribute the file — each download is fingerprinted to your
   BuiltByBit account.
2. Drop the jar into `plugins/` and start the server, exactly as you
   would for the free build.
3. You do **not** need to set `mineskin.apiKey` in `config.yml`. The
   paid build ignores that field at runtime; all uploads go through the
   Tessera backend.

That's the whole install. Everything in [Configuration](/configuration)
applies unchanged, including `chunkGridSize`, animation settings, and
the materials allow-/deny-list.

## `/tessera archives`

The paid version includes a `/tessera archives` subcommand
([reference](/commands#tessera-archives-list-download-id-paid-build-only)):

```text
/tessera archives list
/tessera archives download <id>
```

`list` fetches a JSON index of available archives — each entry includes
its id, name, target Minecraft version, target `chunkGridSize`, and
size. `download <id>` streams the chosen archive into
`plugins/Tessera/heads/` and calls the addon-pack loader so the pack
goes live without a restart.

Archive downloads are gated on the BuiltByBit license fingerprint
embedded in the jar. If you copy the jar to another server, the same
license fingerprint will appear on both — the backend logs that
crossover and the author may revoke the license. There's no per-server
activation flow to manage; it just works on a server you own.

## Free → paid migration

There is no migration. You can switch between the two builds at any
time by swapping the jar; the on-disk caches
(`plugins/Tessera/cache/heads-<gridN>/`) and addon packs
(`plugins/Tessera/heads/`) are read by both. Going from paid → free
keeps the runtime cache intact but the `/tessera archives` command
becomes a no-op.

