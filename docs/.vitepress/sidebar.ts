import { DefaultTheme } from "vitepress";

export const sidebar: DefaultTheme.Sidebar = {
	'/guide/': { base: '/guide/', items: sidebarGuide() },
	'/tools/': { base: '/tools/', items: sidebarTools() },
}

function sidebarGuide(): DefaultTheme.SidebarItem[] {
	return [
		{
			text: 'Introduction',
			collapsed: false,
			items: [
				{ text: 'Introduction', link: '/introduction' },
				{ text: 'Getting Started', link: '/get-started' },
				{ text: 'Core API', link: '/core-api' },
			],
		},
		{
			text: 'Client API',
			collapsed: false,
			items: [
				{ text: 'Client API', link: '/client-api' },
				{ text: 'Peers and Signaling', link: '/client/peers' },
				{ text: 'Threads', link: '/client/threads' },
				{ text: 'Sending Media', link: '/client/sending-media' },
				{ text: 'Receiving Media', link: '/client/receiving-media' },
				{ text: 'Transforms and Encryption', link: '/client/transforms' },
				{ text: 'Data Channels', link: '/client/data-channels' },
				{ text: 'WHIP and WHEP', link: '/client/whip-whep' },
				{ text: 'Statistics and Call Checks', link: '/client/statistics' },
			],
		},
		{
			text: 'Media Basics',
			collapsed: false,
			items: [
				{ text: 'Media Devices', link: '/media/media-devices' },
				{ text: 'Media Constraints', link: '/media/constraints' },
				{ text: 'Media Directionality', link: '/media/directionality' },
				{ text: 'Media Files', link: '/media/media-files' },
				{ text: 'Media Recording', link: '/media/media-recording' },
			],
		},
		{
			text: 'Audio',
			collapsed: false,
			items: [
				{ text: 'Audio Devices', link: '/audio/audio-devices' },
				{ text: 'Audio Processing', link: '/audio/audio-processing' },
				{ text: 'Custom Audio Source', link: '/audio/custom-audio-source' },
				{ text: 'System Audio Source', link: '/audio/system-audio-source' },
				{ text: 'Headless Audio', link: '/audio/headless-audio' },
				{ text: 'DTMF Sender', link: '/audio/dtmf-sender' },
			],
		},
		{
			text: 'Video',
			collapsed: false,
			items: [
				{ text: 'Camera Video', link: '/video/camera-capture' },
				{ text: 'Desktop Video', link: '/video/desktop-capture' },
				{ text: 'Custom Video Source', link: '/video/custom-video-source' },
			],
		},
		{
			text: 'Data Communication',
			collapsed: false,
			items: [
				{ text: 'Data Channels', link: '/data/data-channels' },
				{ text: 'File Transfer', link: '/data/file-transfer' },
			],
		},
		{
			text: 'Networking and ICE',
			collapsed: false,
			items: [
				{ text: 'Peer Connection Configuration', link: '/networking/peer-connection-config' },
				{ text: 'Port Allocator Configuration', link: '/networking/port-allocator-config' },
			],
		},
		{
			text: 'Monitoring and Debugging',
			collapsed: false,
			items: [
				{ text: 'RTC Stats', link: '/monitoring/rtc-stats' },
				{ text: 'Logging', link: '/monitoring/logging' },
			],
		},
		{
			text: 'Advanced',
			collapsed: false,
			items: [
				{ text: 'Field Trials', link: '/advanced/field-trials' },
				{ text: 'Encoded Transforms', link: '/advanced/encoded-transforms' },
				{ text: 'End-to-End Encryption', link: '/advanced/end-to-end-encryption' },
				{ text: 'Video Codecs', link: '/advanced/video-codecs' },
				{ text: 'Audio Codecs', link: '/advanced/audio-codecs' },
				{ text: 'GraalVM Native Image', link: '/advanced/native-image' },
			],
		},
		{
			text: 'Background',
			collapsed: false,
			items: [
				{ text: 'Client API Design', link: '/client-api-design' },
				{ text: 'Build Notes', link: '/build' },
			],
		},
	]
}

function sidebarTools(): DefaultTheme.SidebarItem[] {
	return [
		{
			text: 'Audio',
			collapsed: false,
			items: [
				{ text: 'Audio Converter', link: '/audio/audio-converter' },
				{ text: 'Audio Recorder', link: '/audio/audio-recorder' },
				{ text: 'Audio Player', link: '/audio/audio-player' },
				{ text: 'Voice Activity Detection', link: '/audio/voice-activity-detector' },
			],
		},
		{
			text: 'Video',
			collapsed: false,
			items: [
				{ text: 'Video Buffer Converter', link: '/video/video-buffer-converter' },
				{ text: 'Video Capture', link: '/video/video-capturer' },
			],
		},
		{
			text: 'Desktop',
			collapsed: false,
			items: [
				{ text: 'Screen Capturer', link: '/desktop/screen-capturer' },
				{ text: 'Window Capturer', link: '/desktop/window-capturer' },
				{ text: 'Power Management', link: '/desktop/power-management' },
			],
		},
	]
}