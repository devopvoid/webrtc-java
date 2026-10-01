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

features:
  - title: Cross-platform
    details: Use the same Java API across Windows, macOS, and Linux with prebuilt native bindings. Ideal for desktop apps and server‑side media services.
  - title: Native performance
    details: Thin JNI layer with minimal overhead, delivering near-native performance with minimal context switching between Java and native code.
  - title: Audio and video streaming
    details: Audio and video capture from cameras and microphones devices, with support for custom media sources for flexible streaming solutions.
  - title: Media File Playback
    details: Send video and audio files over a peer connection. The optional media module decodes with FFmpeg in native code and paces playback in real time, keeping audio and video in sync.
  - title: Call Recording
    details: Record what a peer connection sends or receives into MKV, WebM or MP4 files. Encoded frames go into the file as they are, without re-encoding, so recording costs next to no CPU and keeps the exact quality of the call.
  - title: End-to-End Encryption
    details: Encoded transforms let Java code read, change or drop every encoded frame between encoder and network, like insertable streams in the browser; the building block for end-to-end encryption, frame metadata and stream analysis.
  - title: Custom Video Codecs
    details: Plug video encoders and decoders written in Java into WebRTC, next to the built-in VP8, VP9, AV1 and H.264, to bring in a codec of your own or a hardware encoder from another library.
  - title: Screen Sharing
    details: Share application windows or the full desktop with minimal setup; integrate screen capture streams like any other media stream.
  - title: Data Channels
    details: Reliable and unordered/ordered SCTP data channels for arbitrary messaging, file transfer, and app signaling.
  - title: Statistics API for monitoring
    details: Access WebRTC stats (bitrate, packet loss, jitter, RTT, frame rate, CPU) to monitor and optimize media quality.
---

