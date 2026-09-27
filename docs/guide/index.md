# Guide Overview

This section provides detailed guides for various features of the webrtc-java library.

## Media Basics

- [Media Devices](/guide/media/media-devices) - Working with audio and video devices
- [Bitrate and Framerate Constraints](/guide/media/constraints) - Controlling media quality
- [Send-only and Receive-only](/guide/media/directionality) - Configure transceiver directions (send-only, receive-only or inactive)
- [Media Files](/guide/media/media-files) - Sending video and audio files instead of a camera and microphone
- [Media Recording](/guide/media/media-recording) - Recording what a call sends or receives into a media file, without re-encoding

## Audio

- [Audio Device Selection](/guide/audio/audio-devices) - Selecting and configuring audio devices
- [Audio Processing](/guide/audio/audio-processing) - Voice processing components
- [Custom Audio Source](/guide/audio/custom-audio-source) - Using custom audio sources with WebRTC
- [Headless Audio](/guide/audio/headless-audio) - Playout pull without touching real OS audio devices
- [DTMF Sender](/guide/audio/dtmf-sender) - Sending DTMF tones in a call

## Video

- [Camera Capture](/guide/video/camera-capture) - Capturing video from cameras
- [Desktop Capture](/guide/video/desktop-capture) - Capturing and sharing screens and windows
- [Custom Video Source](/guide/video/custom-video-source) - Using custom video sources with WebRTC

## Data Communication

- [Data Channels](/guide/data/data-channels) - Sending and receiving arbitrary data between peers

## Networking and ICE

- [Port Allocator Config](/guide/networking/port-allocator-config) - Restrict ICE port ranges and control candidate gathering behavior

## Monitoring and Debugging

- [RTC Stats](/guide/monitoring/rtc-stats) - Monitoring connection quality and performance
- [Logging](/guide/monitoring/logging) - Configuring and using the logging system

## Advanced

- [Field Trials](/guide/advanced/field-trials) - Enabling experimental features and tuning WebRTC internals
- [Encoded Transforms](/guide/advanced/encoded-transforms) - Reading and changing encoded frames, e.g. for end-to-end encryption

## Additional Resources

For a complete API reference, check the [JavaDoc](https://javadoc.io/doc/dev.onvoid.webrtc/webrtc-java/latest/index.html).