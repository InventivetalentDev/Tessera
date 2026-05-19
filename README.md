# Tessera  

**Tessera** replaces the vanilla block-breaking animation with a 3D voxel-shatter effect - **entirely server-side, with no client mods or resource packs required**.

![Example of breaking oak planks](https://imagedelivery.net/3uwxrP7hx2SHdBFF5lTuXg/d191cbd1-138f-4a8f-7a64-7e20f9134800/w=800)

 <!-- <iframe width="560" height="315" src="https://www.youtube-nocookie.com/embed/ky2vsu0c2FY" title="YouTube video player" frameborder="0" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe> -->

---

[![builtbybit](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/builtbybit_vector.svg)](https://builtbybit.com/resources/tessera.107243/)
[![modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/compact/available/modrinth_vector.svg)](https://modrinth.com/plugin/tessera)


## Features
- Per-block direction-aware shrink animation based on the player's view
- Customizable detail level - voxel grid density can be adjusted between 1 - 16 voxels (default 4 - note: higher values may cause performance issues for players)
- Highly optimized for server performance and player experience 
- Powered by [MineSkin](https://mineskin.org) 

---

**Important Note:** This plugin requires a **MineSkin API key**. A paid subscription is **strongly recommended** to reduce the time it takes to set up new blocks.  
> Disclosure: I (inventivetalent) own and manage both this plugin and MineSkin.

---

![Example of breaking oak planks, different angle](https://imagedelivery.net/3uwxrP7hx2SHdBFF5lTuXg/54eeaf2d-6a49-440d-c911-40bb8cb4ec00/w=800)

## Getting Started
*Please see the docs for a full [Getting Started guide](https://tessera.inventivetalent.org/getting-started)*
- Download the .jar and drop it into your plugins folder
- Restart the server
- Get an API key from MineSkin
- Edit the plugin config.yml, and set the API key
- Restart/reload the server
- Use `/tessera test <block>` to try it out or just start breaking blocks! 

 ### Limitations
 - Only supports vanilla block textures from [mcasset.cloud](https://mcasset.cloud)
	 - Players using custom resource packs will see the default texture while breaking blocks
 - Generating the skin signatures for player heads takes time. Paid MineSkin plans make this much faster, but will still take somewhere between a few seconds to a couple of minutes.
 - The plugin ships with a handful of pre-baked skin signatures for the most common blocks (at default grid size 4) - everything else will have to go through MineSkin first.
 - Block break sounds may sound off due to the block being replaced with a barrier while breaking.
 - Currently only supports cube-blocks - support for e.g. stairs, slabs and other block shapes may be added in the future.


> Creation of this plugin was heavily AI-assisted.

---

This plugin collects anonymous usage statistics via [bStats](https://bstats.org/plugin/bukkit/Tessera/31093). You can disable this by setting `metrics: false` in `config.yml`.
