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
      { text: 'Paid Build', link: '/paid-mode' },
      { text: 'Extension API', link: '/extension-api' },
    ],
    sidebar: [
      {
        text: 'Guide',
        items: [
          { text: 'Getting Started', link: '/getting-started' },
          { text: 'Paid Build', link: '/paid-mode' },
        ],
      },
      {
        text: 'Reference',
        items: [
          { text: 'Configuration', link: '/configuration' },
          { text: 'Commands', link: '/commands' },
        ],
      },
      {
        text: 'Development',
        items: [
          { text: 'Extension API', link: '/extension-api' },
          { text: 'Debug Commands', link: '/debug-commands' },
        ],
      },
    ],
    search: {
      provider: 'local',
    },
    footer: {
      copyright: 'Tessera plugin documentation',
    },
  },
});
