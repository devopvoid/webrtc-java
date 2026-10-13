import { DefaultTheme } from "vitepress";
import { IS_NEXT, NEXT_BASE, SITE_URL } from './channel'
import { PROJECT_VARS } from './versions'

export const navbar: DefaultTheme.NavItem[] = [
	{
		text: 'Guide',
		link: '/guide/',
	},
	{
		text: 'Examples',
		link: '/guide/examples',
	},
	{
		text: 'Tools',
		link: '/tools',
	},
	{
		text: IS_NEXT ? PROJECT_VARS.VERSION_SNAPSHOT : PROJECT_VARS.VERSION,
		items: [
			// Absolute, since each channel is a site of its own, with its own base.
			IS_NEXT
				? { text: `Latest release (${PROJECT_VARS.VERSION})`, link: SITE_URL }
				: { text: 'Unreleased (main)', link: new URL(NEXT_BASE, SITE_URL).href },
			{
				text: 'Changelog',
				link: 'https://github.com/devopvoid/webrtc-java/blob/main/CHANGELOG.md'
			},
			{
				text: 'Build Notes',
				link: '/guide/build'
			}
		]
	}
]