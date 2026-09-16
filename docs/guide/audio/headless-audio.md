# Headless Audio

The `HeadlessAudioDeviceModule` is a convenience implementation of the `AudioDeviceModule` that uses WebRTC's dummy audio layer. It avoids touching real OS audio devices while still driving the WebRTC render pipeline, which is what delivers received audio to an `AudioTrackSink`. It has no capture path; audio you want to send goes through a `CustomAudioSource`.

This is ideal for:
- Server-side or CI environments without audio hardware
- Automated tests where deterministic, no-op audio IO is desired
- Receive-only applications that should render audio via the WebRTC pipeline without producing audible output
- Applications that implement custom audio ingestion but do not want to interact with system devices

## Key characteristics
- Uses dummy audio drivers; no real system devices are opened
- Exposes at least one dummy playout and recording device to allow initialization
- Supports the playout and recording start/stop lifecycle, though recording carries no audio
- Intended primarily for headless scenarios where you want the WebRTC audio pipelines to run without touching physical devices

---

## Playout path

Create the module and pass it to the `PeerConnectionFactory`. This ensures your peer connection stack uses a headless (dummy) audio backend.

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

::: info
- Calling startPlayout without a prior initPlayout will throw an error. Always call initPlayout first.
- If you only need the audio pipeline to be ready when remote audio arrives, you may delay playout initialization until after creating your RTCPeerConnection.
:::


## Sending audio

The module has no capture path. There is no device to capture from, so `initRecording()` and `startRecording()` only move the module through the recording lifecycle and deliver no audio. Audio you want to send goes through a `CustomAudioSource`, which hands it to the track's senders directly.

::: info
Earlier versions pulled the render mix back through the module and handed it to WebRTC as captured audio. That made a peer connection send the audio it had just received from the remote peer straight back to it, and it fed the send stream a second time alongside the pushed audio, which aborted the process inside WebRTC. Calling `startRecording()` is now harmless but does nothing.
:::

Typical steps:

```java
HeadlessAudioDeviceModule adm = new HeadlessAudioDeviceModule();

PeerConnectionFactory factory = new PeerConnectionFactory(adm);

// Playout drives the receive side of every peer connection of this factory,
// so start it even when the application only sends.
adm.initPlayout();
adm.startPlayout();

// Use a custom or built-in AudioSource to provide audio frames
CustomAudioSource source = new CustomAudioSource();
AudioTrack senderTrack = factory.createAudioTrack("audio0", source);
RTCRtpSender sender = peerConnection.addTrack(senderTrack, Collections.singletonList("stream0"));

// Push PCM frames into the CustomAudioSource (10 ms chunks work well)
byte[] pcm = new byte[480 /* frames */ * 2 /* ch */ * 2 /* bytes */];
source.pushAudio(pcm, 16, 48000, 2, 480);

// ... later, stop
adm.stopPlayout();
// addTrack() returns an RTCRtpSender that is not owned by the peer
// connection, so dispose it explicitly.
sender.dispose();
source.dispose();
adm.dispose();
factory.dispose();
```

::: info
- Push one chunk of 16-bit PCM per call, with 10 ms being the size WebRTC works with. See [Custom Audio Source](/guide/audio/custom-audio-source) for the format rules.
- Push from a single thread. The audio reaches the sender on the thread that calls `pushAudio`.
- The module exposes one virtual recording device; selection calls succeed with index 0.
- Stereo can be enabled/disabled via the standard ADM methods; by default 1 channel is used.
:::

## When to use HeadlessAudioDeviceModule vs. dummy audio layer on AudioDeviceModule

- Prefer `HeadlessAudioDeviceModule` when you need to receive remote audio frames in a headless environment and consume them via `AudioTrack.addSink(AudioSink)`, or when you need to send audio from a custom source without touching physical devices. Its render thread drives the receive side of every peer connection of the factory, while no real system audio device is opened.
- Using a standard `AudioDeviceModule` with `AudioLayer.kDummyAudio` disables actual audio I/O; the audio pipeline is not started for playout and sinks will typically not receive audio frame callbacks. Use this only when you intentionally do not want any audio delivery (e.g., video‑only or fully custom audio).

Related guides:
- [Audio Device Selection](/guide/audio/audio-devices)
- [Custom Audio Source](/guide/audio/custom-audio-source)

## Limitations and notes
- No real audio is played or captured. Playout frames are pulled from the render pipeline and discarded, and nothing is captured.
- Always follow the lifecycles: `initPlayout()` before `startPlayout()`, and `initRecording()` before `startRecording()`. Stop before dispose.
- Playout may be started before or after the peer connections are created; either order works.
- The library handles native loading internally; instantiate and use the module as shown above.
