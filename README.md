[![Build Status](https://img.shields.io/github/actions/workflow/status/devopvoid/webrtc-java/build.yml?label=Build&logo=github)](https://github.com/devopvoid/webrtc-java/actions)
[![Maven Central](https://img.shields.io/maven-central/v/dev.onvoid.webrtc/webrtc-java?label=Maven%20Central&logo=apache-maven)](https://search.maven.org/artifact/dev.onvoid.webrtc/webrtc-java)

<p align="center">
  <img alt="webrtc-java" width="100px" src="https://jrtc.dev/logo.png" />
  <h2 align="center">Connecting the Java world through WebRTC</h2>
</p>

webrtc-java is a Java wrapper for the [WebRTC Native API](https://webrtc.github.io/webrtc-org/native-code/native-apis), providing similar functionality to the [W3C JavaScript API](https://w3c.github.io/webrtc-pc). It allows Java developers to build real-time communication applications for desktop platforms without having to work directly with native code.

The library provides a comprehensive set of Java classes that map to the WebRTC C++ API, making it possible to establish peer-to-peer connections, transmit audio and video, share screens, and exchange arbitrary data between applications.

## Features

- **Complete WebRTC API implementation** - Includes peer connections, media devices, data channels, and more
- **Cross-platform support** - Works on Windows, macOS, and Linux (x64, ARM, ARM64)
- **Media capabilities** - Audio and video capture from cameras and microphones
- **Desktop capture** - Screen and application window sharing
- **Media file playback** - Send video and audio files over a peer connection in place of a camera and microphone, with the optional FFmpeg-based `webrtc-java-media` module
- **Call recording** - Record what a peer connection sends or receives into MKV, WebM or MP4 files, without re-encoding, with the `webrtc-java-media` module
- **Encoded transforms** - Read, change or drop encoded audio and video frames on their way through a sender or receiver, e.g. for end-to-end encryption, like insertable streams in the browser
- **Data channels** - Bidirectional peer-to-peer data exchange
- **Statistics API** - Detailed metrics for monitoring connection quality
- **Simple integration** - Available as a Maven dependency
- **Native performance** - Thin JNI layer with minimal overhead

## Getting Started

For more detailed information, check out the documentation:

- [Quickstart](https://jrtc.dev/guide/get-started) - Get up and running quickly with webrtc-java
- [Guides](https://jrtc.dev/guide/) - Comprehensive documentation on using the library
- [Examples](https://jrtc.dev/guide/examples) - Sample code demonstrating various features
- [Media Files](https://jrtc.dev/guide/media/media-files) - Sending video and audio files with the media module
- [Media Recording](https://jrtc.dev/guide/media/media-recording) - Recording calls into media files with the media module
- [Encoded Transforms](https://jrtc.dev/guide/advanced/encoded-transforms) - Reading and changing encoded frames, e.g. for end-to-end encryption
- [Video Codecs](https://jrtc.dev/guide/advanced/video-codecs) - Adding video codecs implemented in Java, or limiting the built-in ones
- [Build Notes](https://jrtc.dev/guide/build) - Instructions for building the library from source

## License

Copyright (c) 2019 Alex Andres

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

[http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.