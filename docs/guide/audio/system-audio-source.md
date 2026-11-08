# System Audio Source

`SystemAudioSource` captures audio playing on the system output. It is a
`CustomAudioSource` and can be passed directly to `PeerConnectionFactory.createAudioTrack`.
It sends 16-bit, 48 kHz stereo PCM in 10 ms chunks, independently of screen or
window video capture. Capturing a window does **not** restrict audio to that window.

## Platform support

| Platform | Capture backend | Requirements and scope |
| --- | --- | --- |
| Windows | WASAPI shared-mode loopback | Captures the default multimedia output selected at start, including audio from this process. |
| Linux | PulseAudio output monitor | Requires `libpulse.so.0` and PulseAudio or PipeWire with `pipewire-pulse`. Captures the default sink selected at start, including audio from this process. A native PipeWire portal audio backend is not provided. |
| macOS 13+ | ScreenCaptureKit | Requires screen/audio recording permission and a shareable display. Captures system audio excluding this process. Build with a macOS 13+ SDK. |

No shell commands, virtual audio driver, or FFmpeg installation are required.
There is no application/process selection API in this version. Protected content
and platform recording restrictions still apply.

## Send alongside a screen track

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

`start()` waits for capture to open and throws `IllegalStateException` on startup
failure, with the platform error as its cause. Call it on a background thread,
particularly when macOS may show a permission prompt. Platform startup waits are
bounded; permission dialogs can remain open after a timeout. A late macOS grant
will not leave this timed-out capture recording in the background. Grant permission
in System Settings and retry, restarting the application if the OS requires it.

When the call ends, stop recording before releasing tracks and the source:

```java
systemAudio.stop();
peerConnection.removeTrack(audioSender);
audioSender.dispose();
audioTrack.dispose();
systemAudio.dispose(); // or close(), also supported by try-with-resources
```

Use `try/finally` in application code to release these resources on errors too.
`stop()` and `dispose()` are idempotent. `stop()` keeps the source and its tracks
usable for `start()` again; disposal is permanent. A stop or disposal from a local
audio sink requests shutdown without joining its own callback thread. Do not
restart from an audio callback. The inherited `pushAudio` methods throw
`IllegalStateException`: push other audio into a separate `CustomAudioSource`.

## Device and permission changes

Observe `isRecording()` and `getFailure()` from your application control thread.
A runtime capture failure stops recording and retains its cause in `getFailure()`;
starting again clears that failure. A stopped source does not end its tracks, so
it can resume on the same sender. It does not automatically reopen a device or
silently change recording permissions.

For an output change, call `stop()`, select the new default output through the OS,
and call `start()`. A change of the default output alone does not move a running
capture: Windows and Linux bind the output chosen at startup. Device loss or
permission revocation may only become observable when the platform reports it.

## Synchronization and latency

Capture timestamps use WebRTC's monotonic clock, the same clock as
`SyncClock.currentTimeUs()` and desktop video. WASAPI's packet clock and
ScreenCaptureKit's presentation clock are translated into that clock; PulseAudio
uses its reported capture latency. This preserves the capture time when audio is
buffered before delivery. Queueing is bounded to 100 ms; older chunks are dropped
if delivery stalls. Audio is paced by the OS rather than an independent Java timer.
This does not promise sample-accurate lip synchronization or compensate for all
screen capture, output-device, and receiver latency.

## Microphone and feedback

Send the microphone as a second track of the same factory and connection. Each
sender gets the audio of its own track, so neither track hears the other:

```java
AudioTrackSource microphone = factory.createAudioSource(new AudioOptions());
AudioTrack microphoneTrack = factory.createAudioTrack("microphone", microphone);
peerConnection.addTrack(microphoneTrack, Collections.singletonList("presentation"));
```

The microphone track gets the factory's echo cancellation, noise suppression and
gain control as its `AudioOptions` ask for; the system audio track gets none, and
the two are not mixed. Echo cancellation removes what this application plays, not
what other applications play, so system audio that reaches the microphone through
the speakers is sent twice: use headphones to prevent that.
On Windows and Linux, route received call audio to another output or to a virtual
output that is not monitored. Headphones alone do not prevent a loopback source
from capturing remote audio played on the monitored output. On macOS this process's
playout is excluded by ScreenCaptureKit; remote audio played in another process
can still be captured.

Run `SystemAudioExample` from `webrtc-examples` for a capture-only example that
counts received PCM frames without playing them back. The opt-in
`SystemAudioSourceIntegrationTest` (`-Dtest.systemAudio=true`) needs audio playing
on the default output and recording permission; lifecycle tests use deterministic
backends without hardware.

The platform bindings follow [Microsoft's loopback capture API](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording),
[PulseAudio's recording stream API](https://www.freedesktop.org/software/pulseaudio/doxygen/stream_8h.html),
and [ScreenCaptureKit audio configuration](https://developer.apple.com/documentation/screencapturekit/scstreamconfiguration/capturesaudio).
