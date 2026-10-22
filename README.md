[![Build Status](https://img.shields.io/github/actions/workflow/status/devopvoid/webrtc-java/build.yml?label=Build&logo=github)](https://github.com/devopvoid/webrtc-java/actions)
[![Maven Central](https://img.shields.io/maven-central/v/dev.onvoid.webrtc/webrtc-java?label=Maven%20Central&logo=apache-maven)](https://search.maven.org/artifact/dev.onvoid.webrtc/webrtc-java)
[![Docs](https://img.shields.io/badge/Docs-Next_Version-violet)](https://jrtc.dev/next/)

<p align="center">
  <img alt="webrtc-java" width="100px" src="https://jrtc.dev/logo.png" />
  <h2 align="center">Connecting the Java world through WebRTC</h2>
</p>

webrtc-java is a Java wrapper for the [WebRTC Native API](https://webrtc.github.io/webrtc-org/native-code/native-apis), providing similar functionality to the [W3C JavaScript API](https://w3c.github.io/webrtc-pc). It allows Java developers to build real-time communication applications for desktop platforms without having to work directly with native code.

The library provides a comprehensive set of Java classes that map to the WebRTC C++ API, making it possible to establish peer-to-peer connections, transmit audio and video, share screens, and exchange arbitrary data between applications.

## Features

**Calls and data**

- **[Client API](https://jrtc.dev/guide/client-api)** - High-level calls that negotiate by themselves, keep your code off WebRTC's threads and close with one call
- **[WHIP and WHEP](https://jrtc.dev/guide/client-api#whip-and-whep)** - Publish to and play from streaming servers
- **[Peer connections](https://jrtc.dev/guide/networking/peer-connection-config)** - The full native API, modeled on the W3C JavaScript API
- **[Data channels](https://jrtc.dev/guide/data/data-channels)** - Reliable or unreliable peer-to-peer messaging
- **[Statistics](https://jrtc.dev/guide/monitoring/rtc-stats)** - Detailed metrics on connection quality

**Audio and video**

- **[Cameras and microphones](https://jrtc.dev/guide/media/media-devices)** - Capture from any local device
- **[Screen sharing](https://jrtc.dev/guide/video/desktop-capture)** - Share the full desktop or single application windows
- **[Video processing](https://jrtc.dev/guide/video/camera-capture#changing-frames-before-they-are-sent)** - Blur a background, draw an overlay or blank out part of the screen before frames are sent
- **[Video views](https://jrtc.dev/guide/client-api#handlers-on-the-ui-thread)** - Ready-made components that show video in Swing, or in JavaFX with the `webrtc-java-javafx` module

**Codecs and encoded media**

- **[Hardware codecs](https://jrtc.dev/guide/advanced/video-codecs#hardware-encoding)** - H.264, H.265, VP9 and AV1 on the GPU with NVENC/NVDEC, Media Foundation, VA-API and VideoToolbox, falling back to software
- **[Custom codecs](https://jrtc.dev/guide/advanced/video-codecs#adding-a-codec)** - Plug in video encoders and decoders written in Java, or limit the codecs a factory offers
- **[End-to-end encryption](https://jrtc.dev/guide/advanced/encoded-transforms)** - Read, change or drop encoded audio and video frames, like insertable streams in the browser
- **[Pre-encoded media](https://jrtc.dev/guide/advanced/encoded-transforms#injecting-encoded-frames)** - Send a camera's H.264 stream or a recording without re-encoding it

**Optional modules**

- **[Media playback](https://jrtc.dev/guide/media/media-files)** (`webrtc-java-media`) - Stream video and audio files or RTSP sources into a call, decoded with FFmpeg
- **[Call recording](https://jrtc.dev/guide/media/media-recording)** (`webrtc-java-media`) - Save what a call sends or receives into MKV, WebM or MP4 files, without re-encoding

**Platforms**

Prebuilt native libraries behind a thin JNI layer:

| OS      | x64 | arm64 | arm32 |
|---------|:---:|:-----:|:-----:|
| Windows |  ✓  |   ✓   |       |
| macOS   |  ✓  |   ✓   |       |
| Linux   |  ✓  |   ✓   |   ✓   |

## Getting Started

For more detailed information, check out the documentation:

- [Quickstart](https://jrtc.dev/guide/get-started) - Get up and running quickly with webrtc-java
- [Guides](https://jrtc.dev/guide/) - Comprehensive documentation on using the library
- [Examples](https://jrtc.dev/guide/examples) - Sample code demonstrating various features
- [Build Notes](https://jrtc.dev/guide/build) - Instructions for building the library from source
- [Unreleased Docs](https://jrtc.dev/next/) - Documentation of the main branch, including features not released yet

## License

Copyright (c) 2019-2026 Alex Andres

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

[http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
