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
				{ text: 'What is webrtc-java?', link: '/introduction' },
				{ text: 'Getting Started', link: '/get-started' },
			],
		},
		{
			text: 'Media Basics',
			collapsed: false,
			items: [
				{ text: 'Media Devices', link: '/media/media-devices' },
				{ text: 'Media Constraints', link: '/media/constraints' },
				{ text: 'Media Directionality', link: '/media/directionality' },
			],
		},
		{
			text: 'Audio',
			collapsed: false,
			items: [
				{ text: 'Audio Devices', link: '/audio/audio-devices' },
				{ text: 'Audio Processing', link: '/audio/audio-processing' },
				{ text: 'Custom Audio Source', link: '/audio/custom-audio-source' },
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
			],
		},
		{
			text: 'Networking and ICE',
			collapsed: false,
			items: [
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
				{ text: 'Voice Activity Detector', link: '/audio/voice-activity-detector' },
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
				{ text: 'Window Capturer ', link: '/desktop/window-capturer' },
				{ text: 'Power Management', link: '/desktop/power-management' },
			],
		},
	]
}