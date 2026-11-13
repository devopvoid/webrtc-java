# Introduction

webrtc-java is a Java wrapper for the [WebRTC Native API](https://webrtc.github.io/webrtc-org/native-code/native-apis), providing similar functionality to the [W3C JavaScript API](https://w3c.github.io/webrtc-pc). It allows Java developers to build real-time communication applications for desktop platforms without having to work directly with native code.

The library provides a comprehensive set of Java classes that map to the WebRTC C++ API, making it possible to establish peer-to-peer connections, transmit audio and video, share screens, and exchange arbitrary data between applications.

## Which API?

The library has two APIs:

| | Client API | Core API |
|---|---|---|
| Package | `dev.onvoid.webrtc.client` | `dev.onvoid.webrtc` |
| Negotiation | Runs by itself | Your code exchanges offers, answers and candidates |
| Threads | Your handlers never run on a WebRTC thread | Callbacks run on WebRTC's threads |
| Cleanup | Closing the runtime closes everything | You dispose every native object |
| Use it for | Most applications: start here | Full control over WebRTC's classes |

The [Client API](/guide/client-api) is built on the core API and can reach it where it needs to. Most guides under Media, Audio, Video and Networking show the core API; a note at their top names the client API page for the same task.

## Overview

For more detailed information, check out the documentation:

- [Getting Started](/guide/get-started) - Get up and running quickly with webrtc-java
- [Guides](/guide/) - Comprehensive documentation on using the library
- [Examples](/guide/examples) - Sample code demonstrating various features
- [Build Notes](/guide/build) - Instructions for building the library from source

## License

webrtc-java is licensed under the [Apache License, Version 2.0](https://github.com/devopvoid/webrtc-java/blob/main/LICENSE).
