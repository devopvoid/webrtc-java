import { HeadConfig } from "vitepress/types/shared";

export const head: HeadConfig[] = [
	['link', { rel: "icon", type: "image/png", sizes: "96x96", href: "/favicon-96x96.png"}],
	['link', { rel: "icon", type: "image/svg+xml", href: "/favicon.svg"}],
	['link', { rel: "shortcut icon", href: "/favicon.ico"}],
	['link', { rel: "apple-touch-icon", sizes: "180x180", href: "/apple-touch-icon.png"}],
	['link', { rel: "manifest", href: "/site.webmanifest"}],
	['meta', { name: "apple-mobile-web-app-title", content: "JRTC"}],

	// Security headers
	[
		'meta',
		{
			'http-equiv': 'Content-Security-Policy',
			content:
				"default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self' https:;",
		},
	],
	['meta', { 'http-equiv': 'X-Content-Type-Options', content: 'nosniff' }],
	['meta', { 'http-equiv': 'X-Frame-Options', content: 'SAMEORIGIN' }],
	['meta', { 'http-equiv': 'X-XSS-Protection', content: '1; mode=block' }],
	['meta', { name: 'referrer', content: 'strict-origin-when-cross-origin' }],
]