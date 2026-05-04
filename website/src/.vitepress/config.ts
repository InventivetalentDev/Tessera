import { defineConfig } from 'vitepress';

export default defineConfig({
  title: 'Tessera',
  description:
    'Tessera replaces the vanilla block-break animation with a per-chunk player-head display lattice on PaperMC servers.',
  cleanUrls: true,
  outDir: '../dist',
  lastUpdated: true,
  themeConfig: {
    nav: [
      { text: 'Getting Started', link: '/getting-started' },
      { text: 'Configuration', link: '/configuration' },
      { text: 'Commands', link: '/commands' },
      { text: 'Permissions', link: '/permissions' },
      {
        text: 'GitHub',
        link: 'https://github.com/InventivetalentDev/BlockBreakPhysics',
      },
    ],
    sidebar: [
      {
        text: 'Guide',
        items: [{ text: 'Getting Started', link: '/getting-started' }],
      },
      {
        text: 'Reference',
        items: [
          { text: 'Configuration', link: '/configuration' },
          { text: 'Commands', link: '/commands' },
          { text: 'Permissions', link: '/permissions' },
        ],
      },
      {
        text: 'Development',
        items: [{ text: 'Debug Commands', link: '/debug-commands' }],
      },
    ],
    socialLinks: [
      {
        icon: 'github',
        link: 'https://github.com/InventivetalentDev/BlockBreakPhysics',
      },
    ],
    search: {
      provider: 'local',
    },
    footer: {
      message: 'Released under the MIT License.',
      copyright: 'Tessera plugin documentation',
    },
  },
});
