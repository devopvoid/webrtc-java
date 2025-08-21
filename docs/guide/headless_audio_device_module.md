# Headless Audio Device Module

The `HeadlessAudioDeviceModule` is a convenience implementation of the `AudioDeviceModule` that uses WebRTC's dummy audio layer. It avoids touching real OS audio devices while still enabling the WebRTC audio pipeline to pull and render audio frames (headless playout).

This is ideal for:
- Server-side or CI environments without audio hardware
- Automated tests where deterministic, no-op audio IO is desired
- Receive-only applications that should render audio via the WebRTC pipeline without producing audible output
- Applications that implement custom audio ingestion but do not want to interact with system devices

## Key characteristics
- Uses dummy audio drivers; no real system devices are opened
- Exposes at least one dummy playout device to allow playout initialization
- Supports playout initialization and start/stop lifecycle
- Intended primarily for receive-side audio; recording with dummy devices is typically not useful

---

## Basic usage

Create the module and pass it to the PeerConnectionFactory. This ensures your peer connection stack uses a headless (dummy) audio backend.

```java
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;

// Create the headless ADM
HeadlessAudioDeviceModule audioModule = new HeadlessAudioDeviceModule();

// Initialize and start playout
audioModule.initPlayout();
audioModule.startPlayout();

// Create a factory that uses the headless ADM
PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);

// ... use the factory to build peer connections ...

// Cleanup
try {
    audioModule.stopPlayout();
}
catch (Throwable e) {
    // Ignore errors during stopPlayout()
}
finally {
    audioModule.dispose();
    factory.dispose();
}
```

Notes:
- Calling startPlayout without a prior initPlayout will throw an error. Always call initPlayout first.
- If you only need the audio pipeline to be ready when remote audio arrives, you may delay playout initialization until after creating your RTCPeerConnection.

---

## When to use HeadlessAudioDeviceModule vs. dummy audio layer on AudioDeviceModule

- Prefer `HeadlessAudioDeviceModule` when you need to receive remote audio frames in a headless environment and consume them via `AudioTrack.addSink(AudioSink)`. The headless module drives the playout pipeline so AudioTrack sinks receive callbacks, while no real system audio device is opened.
- Using a standard `AudioDeviceModule` with `AudioLayer.kDummyAudio` disables actual audio I/O; the audio pipeline is not started for playout and sinks will typically not receive audio frame callbacks. Use this only when you intentionally do not want any audio delivery (e.g., video‑only or fully custom audio).

Related guides:
- [Audio Device Selection](audio_devices.md)
- [Custom Audio Source](custom_audio_source.md)

---

## Limitations and notes
- No real audio is played; audio frames flow through the WebRTC pipeline only.
- Always follow the lifecycle: initPlayout() before startPlayout(), and stopPlayout() before dispose() when applicable.
- The library handles native loading internally; instantiate and use the module as shown above.
