# Paid build

Tessera is available in two flavours:

- **Free.** Animates blocks
  in the bundled `heads-<gridN>.ztsra` resource out of the box. Anything
  else requires a [MineSkin](https://account.mineskin.org/) API key (subscription recommended)
  in `config.yml` so the plugin can bake new blocks on demand.
- **Paid.** Sold on
  [LemonSqueezy](https://inventivetalent.lemonsqueezy.com/checkout/buy/83b19253-6672-4dab-bb6f-67873446d26c).
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
  without baking them yourself → [**paid version**](https://inventivetalent.lemonsqueezy.com/checkout/buy/83b19253-6672-4dab-bb6f-67873446d26c).

The bundled blocks render identically in both. Performance,
configuration surface, and the rendering pipeline are unchanged.

## Installing the paid build

1. [Purchase Tessera on LemonSqueezy](https://inventivetalent.lemonsqueezy.com/checkout/buy/83b19253-6672-4dab-bb6f-67873446d26c).
   You'll receive your license key by email immediately after checkout.
2. Download the jar (any build — paid and free use the same artifact) and
   drop it into `plugins/`, exactly as you would for the free build.
3. Open `plugins/Tessera/config.yml` (it's created on first start) and
   paste your license key into `license.key`:

   ```yaml
   license:
     key: "38b1460a-5104-4067-a91d-77b872934d51"
   ```
4. `/tessera reload` or restart the server.

You do **not** need to set `mineskin.apiKey` once `license.key` is
configured — uploads route through the Tessera backend. (Setting it
anyway is fine; the plugin honours it and uses your MineSkin account
directly instead.)

Everything in [Configuration](/configuration) applies unchanged,
including `chunkGridSize`, animation settings, and the materials
allow-/deny-list.

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

## License keys, sharing, and revocation

Your license key gates MineSkin uploads through our backend and access
to `/tessera archives`. The first time a server calls the backend with
your key it gets activated against your server's stable install ID —
nothing for you to do, it happens transparently on the first block
break.

Don't share the key — the backend logs every install ID that calls in
with it, and a single key showing up across many distinct servers will
get disabled from the LemonSqueezy dashboard. The next request after
revocation fails with a `403`.

If you run several of your own servers, that's fine; the backend treats
multiple legitimate installs as a normal pattern. The flag is the
*distinct* server count, not the request volume.

## Free → paid migration

There is no migration step. You can switch between the two modes at any
time by editing `license.key` in `config.yml` (set it to enable paid
mode, blank it out to drop back to free) — the on-disk caches
(`plugins/Tessera/cache/heads-<gridN>/`) and addon packs
(`plugins/Tessera/heads/`) are read in both modes. Dropping back to free
keeps the runtime cache intact but the `/tessera archives` command
becomes a no-op.
