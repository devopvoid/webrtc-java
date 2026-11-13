# System Audio Source

`SystemAudioSource` captures the audio playing on the system output. It sends 16-bit, 48 kHz stereo PCM in 10 ms chunks, independently of screen or window video capture. Capturing a window does **not** restrict the audio to that window.

`SystemAudioSource` is a `CustomAudioSource`. It can be passed directly to `PeerConnectionFactory.createAudioTrack`, or to `audioTrack()` of the client API.

## Platform Support

| Platform | Capture backend | Captures | Needs |
| --- | --- | --- | --- |
| Windows | WASAPI shared-mode loopback | The default multimedia output selected at start, including audio from this process. | |
| Linux | PulseAudio output monitor | The default sink selected at start, including audio from this process. | `libpulse.so.0`, and PulseAudio or PipeWire with `pipewire-pulse`. |
| macOS 13+ | ScreenCaptureKit | System audio, excluding this process. | Screen and audio recording permission, a shareable display, and a build with a macOS 13+ SDK. |

- A native PipeWire portal audio backend is not provided.
- No shell commands, virtual audio driver, or FFmpeg installation are required.
- There is no application or process selection API in this version.
- Protected content and platform recording restrictions still apply.

## Send Alongside a Screen Track

With the [Client API](/guide/client-api), pass the source to `audioTrack()`:

```java
SystemAudioSource systemOutput = new SystemAudioSource();
LocalAudio systemAudio = rtc.media().audioTrack("system-audio", systemOutput);
peer.addTrack(systemAudio, "presentation");
systemOutput.start();
```

The source stays yours. Close it only after the track, or after the runtime that closes the track.

### Using the Core API

Use your existing factory and peer connection, before negotiation:

```java
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.SystemAudioSource;
import java.util.Collections;

SystemAudioSource systemAudio = new SystemAudioSource();
AudioTrack audioTrack = factory.createAudioTrack("presentation-audio", systemAudio);
RTCRtpSender audioSender = peerConnection.addTrack(
        audioTrack, Collections.singletonList("presentation"));
// Add your VideoDesktopSource's video track with the same stream ID.
systemAudio.start();
```

When the call ends, stop recording before releasing the tracks and the source:

```java
systemAudio.stop();
peerConnection.removeTrack(audioSender);
audioSender.dispose();
audioTrack.dispose();
systemAudio.dispose(); // or close(), also supported by try-with-resources
```

## Starting and Stopping

`start()` waits for capture to open. If startup fails, it throws an `IllegalStateException`, with the platform error as its cause.

- Call `start()` on a background thread, particularly when macOS may show a permission prompt.
- Platform startup waits are bounded, but permission dialogs can remain open after a timeout. A late macOS grant will not leave this timed-out capture recording in the background.
- Grant permission in System Settings and retry, restarting the application if the OS requires it.

Use `try/finally` in application code, so that these resources are released on errors too.

- `stop()` and `dispose()` are idempotent.
- `stop()` keeps the source and its tracks usable for `start()` again; disposal is permanent.
- A stop or disposal from a local audio sink requests shutdown without joining its own callback thread. Do not restart from an audio callback.
- The inherited `pushAudio` methods throw `IllegalStateException`. Push other audio into a separate `CustomAudioSource`.

## Device and Permission Changes

Observe `isRecording()` and `getFailure()` from your application's control thread.

- A runtime capture failure stops recording, and keeps its cause in `getFailure()`. Starting again clears that failure.
- A stopped source does not end its tracks, so it can resume on the same sender.
- The source does not reopen a device by itself, and does not silently change recording permissions.

A change of the default output alone does not move a running capture: Windows and Linux bind the output chosen at startup. For an output change, call `stop()`, select the new default output through the OS, and call `start()`.

Device loss or permission revocation may only become observable when the platform reports it.

## Synchronization and Latency

Capture timestamps use WebRTC's monotonic clock, the same clock as `SyncClock.currentTimeUs()` and desktop video. WASAPI's packet clock and ScreenCaptureKit's presentation clock are translated into that clock; PulseAudio uses its reported capture latency. This preserves the capture time when audio is buffered before delivery.

- Queueing is bounded to 100 ms. Older chunks are dropped if delivery stalls.
- Audio is paced by the OS, not by an independent Java timer.
- This does not promise sample-accurate lip synchronization. It does not compensate for all screen capture, output device, and receiver latency.

## Microphone and Feedback

Send the microphone as a second track of the same factory and connection. Each sender gets the audio of its own track, so neither track hears the other:

```java
AudioTrackSource microphone = factory.createAudioSource(new AudioOptions());
AudioTrack microphoneTrack = factory.createAudioTrack("microphone", microphone);
peerConnection.addTrack(microphoneTrack, Collections.singletonList("presentation"));
```

The microphone track gets the factory's echo cancellation, noise suppression and gain control, as its `AudioOptions` ask for. The system audio track gets none, and the two are not mixed.

Echo cancellation removes what this application plays, not what other applications play. System audio that reaches the microphone through the speakers is therefore sent twice; use headphones to prevent that.

- On Windows and Linux, route received call audio to another output, or to a virtual output that is not monitored. Headphones alone do not prevent a loopback source from capturing remote audio played on the monitored output.
- On macOS, ScreenCaptureKit excludes this process's playout. Remote audio played in another process can still be captured.

## Examples and Tests

- `SystemAudioExample` from `webrtc-examples` captures only, and counts received PCM frames without playing them back.
- [`ScreenShareAudioExample`](/guide/examples#screen-share-with-audio) sends a screen, the microphone and the system audio over one peer.
- The opt-in `SystemAudioSourceIntegrationTest` (`-Dtest.systemAudio=true`) needs audio playing on the default output, and recording permission. The lifecycle tests use deterministic backends without hardware.

The platform bindings follow [Microsoft's loopback capture API](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording),
[PulseAudio's recording stream API](https://www.freedesktop.org/software/pulseaudio/doxygen/stream_8h.html),
and [ScreenCaptureKit audio configuration](https://developer.apple.com/documentation/screencapturekit/scstreamconfiguration/capturesaudio).
