// https://vitepress.dev/guide/custom-theme
import { h } from 'vue'
import { inBrowser, type Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import NextBanner from './NextBanner.vue'
import DocsHome from './DocsHome.vue'
import ThreadingDiagram from './ThreadingDiagram.vue'
import ApiNote from './ApiNote.vue'
import { movedSection } from './moved'
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
    app.component('ApiNote', ApiNote)

    // A link to a section that moved to a page of its own goes on to that page,
    // in place of the old address, so that going back does not come here again.
    if (inBrowser) {
      const base = siteData.value.base
      const target = (url: URL) => {
        const path = '/' + url.pathname.slice(base.length)
        const moved = movedSection(path, url.hash)
        return moved && base + moved.slice(1)
      }

      // The page that was served is the old one, so the new one is loaded in its place.
      const first = target(new URL(location.href))
      if (first) {
        location.replace(first)
      }

      const previous = router.onBeforeRouteChange
      router.onBeforeRouteChange = (to) => {
        const moved = target(new URL(to, location.href))
        if (moved) {
          router.go(moved, { replace: true })
          return false
        }
        return previous?.(to)
      }
    }
  }
} satisfies Theme
