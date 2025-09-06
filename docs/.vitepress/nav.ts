import { DefaultTheme } from "vitepress";
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
		text: PROJECT_VARS.VERSION,
		items: [
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