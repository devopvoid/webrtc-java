import { HeadConfig } from "vitepress/types/shared";

const isProd = process.env.NODE_ENV === 'production';

const head: HeadConfig[] = [
	['link', { rel: "icon", type: "image/png", sizes: "96x96", href: "/favicon-96x96.png" }],
	['link', { rel: "icon", type: "image/svg+xml", href: "/favicon.svg" }],
	['link', { rel: "shortcut icon", href: "/favicon.ico" }],
	['link', { rel: "apple-touch-icon", sizes: "180x180", href: "/apple-touch-icon.png" }],
	['link', { rel: "manifest", href: "/site.webmanifest" }],
	['meta', { name: "apple-mobile-web-app-title", content: "JRTC" }],
]

if (isProd) {
	head.push([
		'script',
		{
			async: '',
			src: 'https://eu.umami.is/script.js',
			'data-website-id': '126654a9-5f07-4b57-ad7e-023eda3980ff',
			'data-domains': 'jrtc.dev',
			'data-do-not-track': 'true'
		},
	])
}

export { head }