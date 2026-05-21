---
layout: home

hero:
  name: Tessera
  text: Block-break animations, reimagined.
  tagline: A PaperMC plugin that splits broken blocks into a lattice of player-head displays for cinematic break effects.
  image:
    src: https://imagedelivery.net/3uwxrP7hx2SHdBFF5lTuXg/d191cbd1-138f-4a8f-7a64-7e20f9134800/w=800
  actions:
    - theme: brand
      text: Get Started
      link: /getting-started
    - theme: alt
      text: Configuration
      link: /configuration
    - theme: alt
      text: Commands
      link: /commands
    - theme: alt
      text: Paid Build
      link: /paid-mode

features:
  - title: Per-chunk break animations
    details: Each broken block is rendered as an N×N×N lattice of ItemDisplay entities (default 4×4×4 = 64 chunks) that animate independently as the block breaks.
  - title: Drop-in for Paper 1.21.4
    details: Targets the Paper API at 1.21 and tested against 1.21.4. Just drop the jar into plugins/ and start the server.
  - title: Live mining progress
    details: Drives the wave from real mining progress so chunks shrink in time with the player's pickaxe — not after the fact.
  - title: Pre-baked + on-demand textures
    details: A curated set of blocks ships pre-baked. Anything else is baked on demand via MineSkin once an API key is configured.
  - title: Configurable density
    details: Tune the chunk grid (1, 2, 4, 8, 16) and concurrent-FakeBlock cap to match your server's performance budget.
  - title: Debug command surface
    details: Built-in /tessera debug commands let server operators tune rotations, flips, and texture overrides live in-game.
  - title: Paid version available
    details: A LemonSqueezy-licensed paid version skips the MineSkin API-key requirement and adds on-demand pre-baked archive downloads.
---

---

Tessera and [MineSkin](https://mineskin.org) are both developed by [inventivetalent](https://inventivetalent.org).
