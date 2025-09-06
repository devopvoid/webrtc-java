# Tools Overview

This section collects small, focused helpers that make it easier to work with audio, video, and desktop capture features without wiring a full PeerConnection. Each utility provides a narrow, well‑defined API with simple start/stop lifecycles and is safe to integrate into custom pipelines.

Use these pages to pick the right tool and jump straight to code examples.

## Audio

- [Audio Converter](/tools/audio/audio-converter) — Resample and remix 10 ms PCM frames between rates and channel layouts.
- [Audio Recorder](/tools/audio/audio-recorder) — Capture microphone input and receive 10 ms PCM frames via an `AudioSink`.
- [Audio Player](/tools/audio/audio-player) — Play PCM audio to an output device by supplying frames via an `AudioSource`.
- [Voice Activity Detector](/tools/audio/voice-activity-detector) — Estimate speech probability for short PCM frames.

## Video

- [Video Buffer Converter](/tools/video/video-buffer-converter) — Convert between I420 and common FourCC pixel formats (e.g., RGBA, NV12).
- [Video Capture](/tools/video/video-capturer) — Control a camera device, configure capabilities, and deliver frames to a sink.

## Desktop capture

- [Screen Capturer](/tools/desktop/screen-capturer) — Enumerate and capture full desktop screens/monitors.
- [Window Capturer](/tools/desktop/window-capturer) — Enumerate and capture individual application windows.
- [Power Management](/tools/desktop/power-management) — Keep the display awake during capture/presentations.

## How these fit together

- Capture: Use Video Capture for cameras, Screen/Window Capturer for desktop sources.
- Process/Analyze: Convert pixel formats with Video Buffer Converter; analyze audio with Voice Activity Detector.
- I/O: Use Audio Recorder to ingest microphone PCM; Audio Player to render PCM.
- System integration: Use Power Management to prevent the OS from sleeping during long running sessions.

## Related guides

- [Camera Capture](/guide/video/camera-capture)
- [Desktop Capture](/guide/video/desktop-capture)
- [Custom Video Source](/guide/video/custom-video-source)
- [Audio Device Selection](/guide/audio/audio-devices)
- [Audio Processing](/guide/audio/audio-processing)
- [RTC Stats](/guide/monitoring/rtc-stats)

For API details, see the [JavaDoc](https://javadoc.io/doc/dev.onvoid.webrtc/webrtc-java/latest/index.html).
