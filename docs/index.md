---
layout: home

hero:
  name: "webrtc-java"
  text: "Java native interface for WebRTC"
  tagline: Build real‑time audio, video, and data with a clean Java API.
  actions:
    - theme: brand
      text: Get Started
      link: /guide/get-started
    - theme: alt
      text: What is webrtc-java?
      link: /guide/introduction
    - theme: alt
      text: Sponsor this project
      link: https://buymeacoffee.com/devopvoid
  image:
    src: /logo.png
    alt: webrtc-java

# Icons from Lucide (https://lucide.dev, ISC license). Keep the number of features a multiple
# of three, so that the default theme lays them out in full rows of three.
features:
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2"/><rect x="9" y="9" width="6" height="6" rx="1"/><path d="M15 2v2M15 20v2M2 15h2M2 9h2M20 15h2M20 9h2M9 2v2M9 20v2"/></svg>
    title: Cross-platform, Native Speed
    details: One Java API on Windows, macOS and Linux, with prebuilt native libraries. A thin JNI layer keeps the overhead between Java and native code minimal.
    link: /guide/introduction
    linkText: Introduction
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5"/><rect x="2" y="6" width="14" height="12" rx="2"/></svg>
    title: Audio and Video Streaming
    details: Capture audio and video from microphones and cameras, or feed your own media sources into a call.
    link: /guide/media/media-devices
    linkText: Media devices
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/></svg>
    title: Screen Sharing
    details: Share application windows or the full desktop with minimal setup, and send them like any other video stream.
    link: /guide/video/desktop-capture
    linkText: Desktop capture
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 3 4 7l4 4M4 7h16M16 21l4-4-4-4M20 17H4"/></svg>
    title: Data Channels
    details: Reliable or unreliable, ordered or unordered SCTP data channels for messaging, file transfer and app signaling.
    link: /guide/data/data-channels
    linkText: Data channels
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2 3 14h9l-1 8 10-12h-9l1-8z"/></svg>
    title: Hardware Video Codecs
    details: Encode and decode H.264, H.265, VP9 and AV1 on the GPU through NVENC and NVDEC, Media Foundation, VA-API and VideoToolbox, with fallback to software.
    link: /guide/advanced/video-codecs#hardware-encoding
    linkText: Hardware codecs
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m16 18 6-6-6-6M8 6l-6 6 6 6"/></svg>
    title: Custom Video Codecs
    details: Plug video encoders and decoders written in Java into WebRTC, to bring in a codec of your own or a hardware encoder from another library.
    link: /guide/advanced/video-codecs#adding-a-codec
    linkText: Video codecs
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 4h-7M10 4H3M21 12h-9M8 12H3M21 20h-5M12 20H3M14 2v4M8 10v4M16 18v4"/></svg>
    title: Video Processing
    details: Change, replace or drop each camera or desktop frame before it is sent, for background blur, overlays, mirroring or hiding part of the screen.
    link: /guide/video/camera-capture#changing-frames-before-they-are-sent
    linkText: Processing frames
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
    title: End-to-End Encryption
    details: Encoded transforms let Java code read, change or drop every encoded frame, like insertable streams in the browser, for encryption, metadata and analysis.
    link: /guide/advanced/encoded-transforms
    linkText: Encoded transforms
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11zM21.854 2.147l-10.94 10.939"/></svg>
    title: Send Pre-encoded Media
    details: Send frames that are already encoded, such as an IP camera's H.264 stream or a recording, without encoding them again in WebRTC.
    link: /guide/advanced/encoded-transforms#injecting-encoded-frames
    linkText: Injecting frames
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><path d="m10 8 6 4-6 4z"/></svg>
    title: Media File Playback
    details: Play video and audio files or RTSP streams into a peer connection. The optional media module decodes with FFmpeg and keeps audio and video in sync.
    link: /guide/media/media-files
    linkText: Media files
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="4" fill="currentColor"/></svg>
    title: Call Recording
    details: Record what a peer connection sends or receives into MKV, WebM or MP4 files, without re-encoding, at next to no CPU cost and in the exact quality of the call.
    link: /guide/media/media-recording
    linkText: Recording
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
    title: Statistics and Monitoring
    details: Read WebRTC stats such as bitrate, packet loss, jitter, round-trip time and frame rate to monitor and tune media quality.
    link: /guide/monitoring/rtc-stats
    linkText: Statistics
---
