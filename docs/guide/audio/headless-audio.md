# Headless Audio

The `HeadlessAudioDeviceModule` is a convenience implementation of the `AudioDeviceModule` that uses WebRTC's dummy audio layer. It avoids touching real OS audio devices while still enabling the WebRTC audio pipeline to pull and render audio frames (headless playout) and to simulate capture (recording path).

This is ideal for:
- Server-side or CI environments without audio hardware
- Automated tests where deterministic, no-op audio IO is desired
- Receive-only applications that should render audio via the WebRTC pipeline without producing audible output
- Applications that implement custom audio ingestion but do not want to interact with system devices

## Key characteristics
- Uses dummy audio drivers; no real system devices are opened
- Exposes at least one dummy playout and recording device to allow initialization
- Supports playout and recording initialization and start/stop lifecycle
- Intended primarily for headless scenarios where you want the WebRTC audio pipelines to run without touching physical devices

---

## Playout path

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

## Recording path (capture)

The headless module also implements a recording path that simulates a microphone. When started, it periodically pulls 10 ms of PCM from the registered AudioTransport (your Java audio source) and feeds it into WebRTC’s capture pipeline. This is particularly useful in tests or server-side senders.

Typical steps:

```java
HeadlessAudioDeviceModule adm = new HeadlessAudioDeviceModule();

// Initialize and start the recording pipeline (capture)
adm.initRecording();
adm.startRecording();

PeerConnectionFactory factory = new PeerConnectionFactory(adm);

// Use a custom or built-in AudioSource to provide audio frames
CustomAudioSource source = new CustomAudioSource();
AudioTrack senderTrack = factory.createAudioTrack("audio0", source);
peerConnection.addTrack(senderTrack, Collections.singletonList("stream0"));

// Push PCM frames into the CustomAudioSource (10 ms chunks work well)
byte[] pcm = new byte[480 /* frames */ * 2 /* ch */ * 2 /* bytes */];
source.pushAudio(pcm, 16, 48000, 2, 480);

// ... later, stop
adm.stopRecording();
adm.dispose();
factory.dispose();
```

Details:
- Initialization order matters: call `initRecording()` before `startRecording()`.
- The module exposes one virtual recording device; selection calls succeed with index 0.
- Stereo can be enabled/disabled via the standard ADM methods; by default 1 channel is used.
- If no AudioTransport is registered (no source), silence is injected to keep timings consistent.

---

## When to use HeadlessAudioDeviceModule vs. dummy audio layer on AudioDeviceModule

- Prefer `HeadlessAudioDeviceModule` when you need to receive remote audio frames in a headless environment and consume them via `AudioTrack.addSink(AudioSink)`, or when you need to send audio from a custom source without touching physical devices. The headless module drives both playout and recording pipelines while no real system audio device is opened.
- Using a standard `AudioDeviceModule` with `AudioLayer.kDummyAudio` disables actual audio I/O; the audio pipeline is not started for playout and sinks will typically not receive audio frame callbacks. Use this only when you intentionally do not want any audio delivery (e.g., video‑only or fully custom audio).

Related guides:
- [Audio Device Selection](/guide/audio/audio-devices)
- [Custom Audio Source](/guide/audio/custom-audio-source)

## Limitations and notes
- No real audio is played or captured; playout frames are pulled from the render pipeline and discarded, and capture frames are pulled from your source (or zeroed) and delivered into WebRTC.
- Always follow the lifecycles: `initPlayout()` before `startPlayout()`, and `initRecording()` before `startRecording()`. Stop before dispose.
- The library handles native loading internally; instantiate and use the module as shown above.
