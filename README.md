[![Build Status](https://img.shields.io/github/actions/workflow/status/devopvoid/webrtc-java/build.yml?label=Build&logo=github)](https://github.com/devopvoid/webrtc-java/actions)
[![Maven Central](https://img.shields.io/maven-central/v/dev.onvoid.webrtc/webrtc-java?label=Maven%20Central&logo=apache-maven)](https://search.maven.org/artifact/dev.onvoid.webrtc/webrtc-java)

# webrtc-java

webrtc-java is a Java wrapper for the [WebRTC Native API](https://webrtc.github.io/webrtc-org/native-code/native-apis), providing similar functionality to the [W3C JavaScript API](https://w3c.github.io/webrtc-pc). It allows Java developers to build real-time communication applications for desktop platforms without having to work directly with native code.

The library provides a comprehensive set of Java classes that map to the WebRTC C++ API, making it possible to establish peer-to-peer connections, transmit audio and video, share screens, and exchange arbitrary data between applications.

## Features

- **Complete WebRTC API implementation** - Includes peer connections, media devices, data channels, and more
- **Cross-platform support** - Works on Windows, macOS, and Linux (x64, ARM, ARM64)
- **Media capabilities** - Audio and video capture from cameras and microphones
- **Desktop capture** - Screen and application window sharing
- **Data channels** - Bidirectional peer-to-peer data exchange
- **Statistics API** - Detailed metrics for monitoring connection quality
- **Simple integration** - Available as a Maven dependency
- **Native performance** - Thin JNI layer with minimal overhead

## Getting Started

For more detailed examples and guides, check out the [quickstart guide](quickstart.md) and the specific examples in the project repository.

## License

Copyright (c) 2019 Alex Andres

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

[http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.