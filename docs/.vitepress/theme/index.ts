// https://vitepress.dev/guide/custom-theme
import { h } from 'vue'
import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import NextBanner from './NextBanner.vue'
import DocsHome from './DocsHome.vue'
import ThreadingDiagram from './ThreadingDiagram.vue'
import './style.css'

export default {
  extends: DefaultTheme,
  Layout: () => {
    return h(DefaultTheme.Layout, null, {
      // https://vitepress.dev/guide/extending-default-theme#layout-slots
      'doc-before': () => h(NextBanner),
      'home-hero-before': () => h(NextBanner),
    })
  },
  enhanceApp({ app, router, siteData }) {
    app.component('DocsHome', DocsHome)
    app.component('ThreadingDiagram', ThreadingDiagram)
  }
} satisfies Theme
