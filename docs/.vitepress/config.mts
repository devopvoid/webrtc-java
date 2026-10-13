import { defineConfig } from 'vitepress'
import { footer } from "./footer";
import { head } from "./head";
import { navbar } from "./nav";
import { sidebar } from "./sidebar";
import { IS_NEXT, NEXT_BASE } from './channel'
import { PROJECT_VARS } from './versions'

export default defineConfig({
	title: "webrtc-java",
	description: "Java native interface for WebRTC",
	cleanUrls: true,
	base: IS_NEXT ? NEXT_BASE : '/',

	head: head,

	themeConfig: {
		logo: {
			light: '/logo.png',
			dark: '/logo.png',
			alt: 'logo'
		},

		externalLinkIcon: true,

		nav: navbar,
		sidebar: sidebar,
		footer: footer,
		socialLinks: [
			{ icon: 'github', link: 'https://github.com/devopvoid/webrtc-java' },
		],
		search: {
			provider: 'local',
		},
	},

	markdown: {
		config: (md) => {
			md.use(variableInterpolationPlugin)
		},
	},
})

function variableInterpolationPlugin(md) {
	md.core.ruler.after('normalize', 'variable-interpolation', (state) => {
		Object.entries(PROJECT_VARS).forEach(([key, value]) => {
			const regex = new RegExp(`{{\\s*${key}\\s*}}`, 'g')
			state.src = state.src.replace(regex, value)
		})
	})
}
