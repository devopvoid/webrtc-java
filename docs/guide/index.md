# Guide Overview

Every guide of the webrtc-java library, in the order of the sidebar. New to the library? Start with [Getting Started](/guide/get-started).

## Introduction

- [Introduction](/guide/introduction) - What the library is, and which of its two APIs to use
- [Getting Started](/guide/get-started) - Installation and a first call with the client API
- [Core API](/guide/core-api) - The same call with the core API, which leaves negotiation, threads and cleanup to you

## Client API

- [Client API](/guide/client-api) - The runtime, closing, errors and reaching the core API
- [Peers and Signaling](/guide/client/peers) - Creating peers, carrying their messages, connection state and ICE recovery
- [Threads](/guide/client/threads) - Where handlers run, limiting threads and the UI thread
- [Sending Media](/guide/client/sending-media) - Cameras, microphones, screens, files and pushed audio and video
- [Receiving Media](/guide/client/receiving-media) - Remote tracks, video frames, video views, audio and recording
- [Transforms and Encryption](/guide/client/transforms) - Encoded transforms and end-to-end encryption of a peer's media
- [Data Channels](/guide/client/data-channels) - Messages between peers, flow control and channel options
- [WHIP and WHEP](/guide/client/whip-whep) - Publishing to and playing from media servers
- [Statistics and Call Checks](/guide/client/statistics) - Connection statistics, diagnosis and checks before a call

## Media Basics

- [Media Devices](/guide/media/media-devices) - Listing microphones, speakers and cameras, and following hotplug events
- [Media Constraints](/guide/media/constraints) - Bitrate, frame rate, resolution, simulcast and priorities of what is sent
- [Media Directionality](/guide/media/directionality) - Send-only, receive-only and inactive transceivers
- [Media Files](/guide/media/media-files) - Sending video and audio files instead of a camera and microphone
- [Media Recording](/guide/media/media-recording) - Recording what a call sends or receives into a media file, into rotating segments, or into a replay buffer, without re-encoding

## Audio

- [Audio Devices](/guide/audio/audio-devices) - Selecting and configuring audio devices
- [Audio Processing](/guide/audio/audio-processing) - Echo cancellation, noise suppression, gain control and other voice processing
- [Custom Audio Source](/guide/audio/custom-audio-source) - Sending audio your application produces
- [System Audio Source](/guide/audio/system-audio-source) - Sending what the system plays, beside a shared screen
- [Headless Audio](/guide/audio/headless-audio) - Playout pull without touching real OS audio devices
- [DTMF Sender](/guide/audio/dtmf-sender) - Sending DTMF tones in a call

## Video

- [Camera Video](/guide/video/camera-capture) - Capturing video from cameras
- [Desktop Video](/guide/video/desktop-capture) - Capturing and sharing screens and windows
- [Custom Video Source](/guide/video/custom-video-source) - Sending video frames your application produces

## Data Communication

- [Data Channels](/guide/data/data-channels) - Sending and receiving arbitrary data between peers with the core API
- [File Transfer](/guide/data/file-transfer) - Sending files with progress, confirmation, checksum and resume

## Networking and ICE

- [Peer Connection Configuration](/guide/networking/peer-connection-config) - Candidate gathering, ICE checks, TURN, SRTP ciphers and other connection settings
- [Port Allocator Configuration](/guide/networking/port-allocator-config) - Restricting ICE port ranges and controlling candidate gathering

## Monitoring and Debugging

- [RTC Stats](/guide/monitoring/rtc-stats) - Monitoring connection quality and performance
- [Logging](/guide/monitoring/logging) - Configuring and using the logging system

## Advanced

- [Field Trials](/guide/advanced/field-trials) - Enabling experimental features and tuning WebRTC internals
- [Encoded Transforms](/guide/advanced/encoded-transforms) - Reading and changing encoded frames, e.g. for end-to-end encryption
- [End-to-End Encryption](/guide/advanced/end-to-end-encryption) - Encrypting a call so that servers forward media they cannot read
- [Video Codecs](/guide/advanced/video-codecs) - Hardware codecs, adding video codecs implemented in Java, or limiting the built-in ones
- [Audio Codecs](/guide/advanced/audio-codecs) - Limiting and ordering the built-in audio codecs
- [GraalVM Native Image](/guide/advanced/native-image) - A single executable that needs no installed Java runtime

## Background

- [Client API Design](/guide/client-api-design) - The decisions behind the client API, its threading model and its full API surface
- [Build Notes](/guide/build) - Building the library from source

## More

- [Tools](/tools/) - Small helpers that work without a peer connection, such as audio players, converters and capturers
- [Examples](/guide/examples) - The example applications of the `webrtc-examples` module
- [JavaDoc](https://javadoc.io/doc/dev.onvoid.webrtc/webrtc-java/latest/index.html) - The complete API reference
