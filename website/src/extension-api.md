# Extension API

Tessera bakes every supported block into an N×N×N lattice of player-head skins
(one MineSkin texture per visible chunk). The **`tessera-api`** artifact lets
*other* plugins reuse that pre-baked data — to build things like block-placing,
miniature-block, or decoration plugins — without bundling any of the data
themselves.

The host server supplies the data: a server with a MineSkin API key or a
[paid license](/paid-mode) bakes blocks on demand, while a server with neither
still serves the blocks bundled in the jar plus any admin-installed addon packs.
Your plugin ships nothing but a `compileOnly` dependency on the interfaces and
talks to whatever the live Tessera install has available.

## Add the dependency

```kotlin
repositories {
    maven("https://repo.inventivetalent.org/repository/public/")
}

dependencies {
    // Provided at runtime by the Tessera plugin — compileOnly, never shaded.
    compileOnly("org.inventivetalent.tessera:tessera-api:<version>")
}
```

Use the `tessera-api` version that matches the Tessera build you target. Then
declare Tessera as a dependency in your `plugin.yml` so it enables first:

```yaml
softdepend: [Tessera]   # or `depend: [Tessera]` if your plugin can't run without it
```

## Get the API

Tessera registers the implementation with Bukkit's `ServicesManager`:

```java
TesseraApi tessera = Tessera.api();          // convenience accessor, may be null
// equivalently:
TesseraApi tessera = Bukkit.getServicesManager().load(TesseraApi.class);

if (tessera == null) {
    getLogger().warning("Tessera not present — block textures unavailable");
    return;
}
```

Query methods are thread-safe. `requestBake` completes off the main thread — hop
back to the main thread before touching Bukkit state in its callback.

## Query what's available

```java
BlockKey stone = BlockKey.of("minecraft:stone");

boolean ready = tessera.isAvailable(stone);
int grid      = tessera.gridN();             // e.g. 4 → 4×4×4 lattice
Set<BlockKey> all = tessera.availableBlocks();
```

## Spawn a block from layout data

`layout(block, blockData)` returns one `ChunkLayout` per visible chunk, already
oriented for the placed blockstate (log axis, stair facing, …) and positioned so
adjacent chunks meet flush. Each entry carries the `Transformation` components
and the texture payload — you spawn and own the entities:

```java
BlockKey key = BlockKey.of("minecraft:oak_log");
BlockData state = Material.OAK_LOG.createBlockData();   // or a real placed state

List<ChunkLayout> chunks = tessera.layout(key, state);
Location origin = block.getLocation();                   // block's NW-down corner

for (ChunkLayout c : chunks) {
    origin.getWorld().spawn(origin, ItemDisplay.class, d -> {
        d.setItemStack(headItem(c.skin()));
        d.setTransformation(new Transformation(
                c.translation(),
                c.leftRotation(),
                new Vector3f(c.scale(), c.scale(), c.scale()),
                c.rightRotation()));
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
    });
}
```

Build the head item from the payload's MineSkin texture property (Paper API):

```java
private ItemStack headItem(SkinPayload skin) {
    ItemStack head = new ItemStack(Material.PLAYER_HEAD);
    SkullMeta meta = (SkullMeta) head.getItemMeta();
    PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), null);
    profile.setProperty(new ProfileProperty("textures",
            skin.textureValue(), skin.textureSignature()));
    meta.setPlayerProfile(profile);
    head.setItemMeta(meta);
    return head;
}
```

Prefer `layout(...)` over hand-rolling positioning: it keeps Tessera's
face-orientation handling in one place. If you only want the raw data, use
`tessera.heads(BakeKey.untinted(key))` for the `ChunkCoord → SkinPayload` map and
do your own geometry.

## Bake blocks on demand

```java
tessera.requestBake(BlockKey.of("minecraft:diamond_block")).thenAccept(outcome -> {
    switch (outcome) {
        case SUCCESS        -> { /* now available — render on the main thread */ }
        case UNBAKEABLE     -> { /* non-cube / unsupported block */ }
        case NOT_CONFIGURED -> { /* server has no MineSkin key or license */ }
        case FAILED         -> { /* transient error — safe to retry */ }
    }
});
```

Baking is the baseline for any configured server, and the licensing path (a
server-set MineSkin key versus a license that proxies uploads — see
[Paid Build](/paid-mode)) is invisible to your plugin. Only `NOT_CONFIGURED`
signals a server that has neither and can therefore serve just its pre-bundled
blocks. `canBakeNewBlocks()` tells you that up front, so you can choose to offer
the full block palette or only `availableBlocks()`.

## React to new bakes

`TesseraBlockBakedEvent` fires on the main thread whenever a runtime bake makes a
new block available (a player break or a `requestBake`). Use it to refresh a
palette instead of polling:

```java
@EventHandler
public void onBaked(TesseraBlockBakedEvent event) {
    getLogger().info("Tessera baked " + event.block() + " at gridN=" + event.gridN());
}
```

## Versioning

`TesseraApi.VERSION` (and `apiVersion()`) is bumped on incompatible changes. The
`tessera-api` artifact tracks the plugin version; depend on the one matching the
Tessera build you target.
