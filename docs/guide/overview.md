# Guides <!-- {docsify-ignore-all} -->

This section provides detailed guides for various features of the webrtc-java library.

## Media Basics

- [Media Devices](guide/media/media_devices.md) - Working with audio and video devices
- [Bitrate and Framerate Constraints](guide/media/constraints.md) - Controlling media quality
- [Send-only and Receive-only](guide/media/send_receive_direction.md) - Configure transceiver directions (send-only, receive-only or inactive)

## Audio

- [Audio Device Selection](guide/audio/audio_devices.md) - Selecting and configuring audio devices
- [Audio Processing](guide/audio/audio_processing.md) - Voice processing components
- [Custom Audio Source](guide/audio/custom_audio_source.md) - Using custom audio sources with WebRTC
- [Headless Audio](guide/audio/headless_audio_device_module.md) - Playout pull without touching real OS audio devices
- [DTMF Sender](guide/audio/dtmf_sender.md) - Sending DTMF tones in a call

## Video

- [Camera Capture](guide/video/camera_capture.md) - Capturing video from cameras
- [Desktop Capture](guide/video/desktop_capture.md) - Capturing and sharing screens and windows
- [Custom Video Source](guide/video/custom_video_source.md) - Using custom video sources with WebRTC

## Data Communication

- [Data Channels](guide/data/data_channels.md) - Sending and receiving arbitrary data between peers

## Networking and ICE

- [Port Allocator Config](guide/networking/port_allocator_config.md) - Restrict ICE port ranges and control candidate gathering behavior

## Monitoring and Debugging

- [RTC Stats](guide/monitoring/rtc_stats.md) - Monitoring connection quality and performance
- [Logging](guide/monitoring/logging.md) - Configuring and using the logging system

## Utilities

- [Audio Converter](guide/utilities/audio_converter.md) - Resample and remix 10 ms PCM frames between different rates and channel layouts
- [Audio Recorder](guide/utilities/audio_recorder.md) - Capture microphone input and receive 10 ms PCM frames via an AudioSink
- [Audio Player](guide/utilities/audio_player.md) - Play PCM audio to an output device by supplying frames via an AudioSource
- [Video Buffer Converter](guide/utilities/video_buffer_converter.md) - Convert between I420 and common FourCC pixel formats (e.g., RGBA, NV12)
- [Video Capture](guide/utilities/video_capturer.md) - Control a camera device, configure capabilities, and deliver frames to a sink
- [Screen Capture](guide/utilities/screen_capturer.md) - Enumerate and capture full desktop screens/monitors
- [Window Capture](guide/utilities/window_capturer.md) - Enumerate and capture individual application windows
- [Voice Activity Detector](guide/utilities/voice_activity_detector.md) - Detect speech activity in PCM audio streams
- [Power Management](guide/utilities/power_management.md) - Prevent the display from sleeping during desktop capture or presentations

## Additional Resources

For a complete API reference, check the [JavaDoc](https://javadoc.io/doc/dev.onvoid.webrtc/webrtc-java/latest/index.html).