// Sections that moved to pages of their own. A link to one of them, from a page
// of an earlier release or from another site, is sent on to where it is now.
export const MOVED_SECTIONS: Record<string, Record<string, string>> = {
	'/guide/client-api': {
		'peers-and-signaling': '/guide/client/peers',
		'signaling': '/guide/client/peers#signaling',
		'polite-and-impolite-peers': '/guide/client/peers#polite-and-impolite-peers',
		'peer-state': '/guide/client/peers#peer-state',
		'automatic-ice-recovery': '/guide/client/peers#automatic-ice-recovery',
		'threads': '/guide/client/threads',
		'limiting-threads': '/guide/client/threads#limiting-threads',
		'handlers-on-the-ui-thread': '/guide/client/threads#handlers-on-the-ui-thread',
		'don-t-wait-in-a-handler': '/guide/client/threads#don-t-wait-in-a-handler',
		'sending-media': '/guide/client/sending-media',
		'sending-a-media-file': '/guide/client/sending-media#sending-a-media-file',
		'pushing-video-and-audio': '/guide/client/sending-media#pushing-video-and-audio',
		'controlling-senders-and-tracks': '/guide/client/sending-media#controlling-senders-and-tracks',
		'switching-cameras': '/guide/client/sending-media#switching-cameras',
		'devices': '/guide/client/sending-media#devices',
		'receiving-media': '/guide/client/receiving-media',
		'video-frames': '/guide/client/receiving-media#video-frames',
		'audio': '/guide/client/receiving-media#audio',
		'receiving-without-sending': '/guide/client/receiving-media#receiving-without-sending',
		'recording': '/guide/client/receiving-media#recording',
		'encoded-transforms': '/guide/client/transforms',
		'data-channels': '/guide/client/data-channels',
		'whip-and-whep': '/guide/client/whip-whep',
		'manual-negotiation': '/guide/client/peers#manual-negotiation',
		'statistics': '/guide/client/statistics',
		'diagnosis-and-quality-events': '/guide/client/statistics#diagnosis-and-quality-events',
		'checking-before-a-call': '/guide/client/statistics#checking-before-a-call',
	},
	'/guide/get-started': {
		'using-the-core-api': '/guide/core-api',
		'initialize-the-webrtc-library': '/guide/core-api#initialize-the-webrtc-library',
		'create-a-peer-connection': '/guide/core-api#create-a-peer-connection',
		'signaling': '/guide/core-api#signaling',
		'letting-the-peer-connection-create-the-description': '/guide/core-api#letting-the-peer-connection-create-the-description',
		'adding-candidates-and-knowing-the-outcome': '/guide/core-api#adding-candidates-and-knowing-the-outcome',
		'media-streams': '/guide/core-api#media-streams',
		'accessing-media-devices': '/guide/core-api#accessing-media-devices',
		'creating-media-tracks': '/guide/core-api#creating-media-tracks',
		'adding-tracks-to-peer-connection': '/guide/core-api#adding-tracks-to-peer-connection',
		'receiving-tracks': '/guide/core-api#receiving-tracks',
		'cleanup': '/guide/core-api#cleanup',
		'finding-objects-that-were-never-disposed': '/guide/core-api#finding-objects-that-were-never-disposed',
	},
}

// Where the section a path and hash name moved to, relative to the site's base, if it did.
export function movedSection(path: string, hash: string): string | undefined {
	const page = path.replace(/\.html$/, '').replace(/\/$/, '')
	const anchor = decodeURIComponent(hash.replace(/^#/, ''))

	return anchor ? MOVED_SECTIONS[page]?.[anchor] : undefined
}
