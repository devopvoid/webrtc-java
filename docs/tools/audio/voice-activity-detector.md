# Voice Activity Detection

Two WebRTC detectors are available. Choose according to the result your
application needs; neither is guaranteed to classify every recording better.
Both analyze mono signed 16-bit PCM and maintain state across audio frames.

| API | Native implementation | Result | Input frames |
| --- | --- | --- | --- |
| `VoiceActivityDetector` | Combined standalone and pitch-based detector | Estimated probability from 0 to 1 | 10 ms |
| `StandaloneVoiceActivityDetector` | Standalone WebRTC VAD directly | Boolean speech decision | 10, 20 or 30 ms |

The existing `VoiceActivityDetector.process()` and `getLastVoiceProbability()`
methods remain available with the original probability estimator. No migration
is required. The standalone detector is an additional class, with its own state.

## Probability estimator

`dev.onvoid.webrtc.media.audio.VoiceActivityDetector` combines the standalone VAD
with pitch and spectral features. Your application chooses a threshold on the
returned probability. The result is delayed by a few chunks and is not updated
on every call. WebRTC initially returns `1.0` until enough audio has arrived;
do not interpret that initial value as observed speech.

```java
import dev.onvoid.webrtc.media.audio.VoiceActivityDetector;

VoiceActivityDetector vad = new VoiceActivityDetector();
try {
    byte[] frame = new byte[320]; // 10 ms, mono signed 16-bit PCM at 16 kHz
    // In your audio loop, fill frame from the source, then:
    vad.process(frame, 160, 16000);
    float probability = vad.getLastVoiceProbability();
    // After the initial buffering period, apply your application's threshold.
    boolean speech = probability > 0.7f; // Example threshold, not a universal setting.
}
finally {
    vad.dispose();
}
```

Pass exactly `sampleRate / 100` samples per call. The byte buffer must contain at
least twice that many bytes; trailing bytes are ignored. Samples are mono signed
16-bit PCM in native byte order, matching the library's audio callbacks. Supported
sample rates are 8000, 16000, 32000 and 48000 Hz; this detector resamples to 16 kHz
internally. Downmix stereo before calling `process()`.

Keep one instance per continuous stream and create a new instance when beginning
a new stream or changing sample rates. The standalone detector's `Mode` setting
does not configure this estimator. Its probability is a model estimate, not a
guarantee that a given percentage of frames will be classified correctly.

## Standalone detector

`dev.onvoid.webrtc.media.audio.StandaloneVoiceActivityDetector` provides direct
boolean decisions and four aggressiveness modes. It does not expose a probability
score, perform channel mixing, or resample input.

```java
import dev.onvoid.webrtc.media.audio.StandaloneVoiceActivityDetector;

StandaloneVoiceActivityDetector vad = new StandaloneVoiceActivityDetector(
        StandaloneVoiceActivityDetector.Mode.AGGRESSIVE);
try {
    short[] frame = new short[160]; // 10 ms of mono audio at 16 kHz
    // Fill frame with PCM samples from your audio source.
    boolean speech = vad.isSpeech(frame, 16000);
}
finally {
    vad.dispose();
}
```

Provide one mono PCM frame in a `short[]` with exactly one of these lengths:

| Sample rate | 10 ms | 20 ms | 30 ms |
| --- | --- | --- | --- |
| 8000 Hz | 80 | 160 | 240 |
| 16000 Hz | 160 | 320 | 480 |
| 32000 Hz | 320 | 640 | 960 |
| 48000 Hz | 480 | 960 | 1440 |

Lengths are sample counts, not bytes. Numeric `short` values have no byte order;
when decoding a PCM `byte[]`, use the source's byte order.

Modes are `QUALITY` (the default), `LOW_BITRATE`, `AGGRESSIVE`, and
`VERY_AGGRESSIVE`. Higher aggressiveness rejects more noise and can miss more
speech. `setMode(mode)` changes aggressiveness without clearing history.

Keep one detector per independent stream and feed frames in order. Do not reset
between frames: the detector uses adaptive state and built-in hangover, so a few
frames after speech ends can still be classified as speech. Call `reset()` when
starting a new stream or changing sample rates; it preserves the selected mode.

Downmix stereo and resample unsupported rates before detection. The
[Audio Converter](/tools/audio/audio-converter) accepts 10 ms frames and can do
both operations. Split larger callbacks or buffer partial callbacks into complete
frames.

## Lifecycle and concurrency

Both constructors load the platform native library. A `PeerConnectionFactory` is
not required. Include the matching native classifier jar as usual.

Calls, including `dispose()`, are serialized on each detector. Callers must still
preserve stream order and avoid modifying an input array during detection.
Disposal is idempotent. Processing or querying a disposed detector throws
`IllegalStateException`, as do standalone configuration/reset calls after disposal.
Invalid frame lengths/rates throw `IllegalArgumentException`; null input buffers
or standalone modes throw `NullPointerException`.

The standalone detector reports failure to allocate its native instance as
`OutOfMemoryError`. The probability estimator retains WebRTC's native
allocation-failure behavior; catching Java exceptions does not guarantee recovery
from native memory exhaustion.

A probability detector and a standalone detector do not share history, even when
they receive the same audio. Feeding, configuring, resetting, or disposing one
does not update the other. Neither API automatically changes encoding, DTX, mute
state, or transmitted audio.

## Audio track integration

The examples module includes `VoiceActivityExample`, an adapter using
`StandaloneVoiceActivityDetector`. It buffers incoming audio into 10 ms frames,
converts mono or stereo input to 16 kHz mono with `AudioConverter`, and reports
each boolean decision. It also supports 44.1 kHz input.

With the [Client API](/guide/client-api), it takes the `AudioChunk`s of an
`onAudio` subscription. To detect speech at the microphone, subscribe to the
microphone track. Its audio arrives after echo cancellation and noise
suppression, so the remote peer's voice from the speakers does not count as
this side speaking:

```java
VoiceActivityExample vad = new VoiceActivityExample(speech -> {
    // Enqueue the decision for your application/UI; return promptly.
});
Subscription audio = microphone.onAudio(Duration.ofMillis(200), vad);
```

The microphone's audio flows while a peer sends the track. For what the remote
peer sends, subscribe to its track:

```java
import dev.onvoid.webrtc.client.Subscription;
import dev.onvoid.webrtc.examples.VoiceActivityExample;

peer.onTrack(track -> {
    if (track.isAudio()) {
        VoiceActivityExample vad = new VoiceActivityExample(speech -> {
            // Enqueue the decision for your application/UI; return promptly.
        });
        Subscription audio = track.asAudio().onAudio(vad);

        track.onEnded(() -> {
            audio.close();
            vad.close();
        });
    }
});
```

An `AudioTrack` of the native API takes the adapter as an `AudioTrackSink`, as
long as the track's source hands its audio to sinks. A remote track and a track
over a `CustomAudioSource` do:

```java
VoiceActivityExample sink = new VoiceActivityExample(speech -> {
    // Enqueue the decision for your application/UI; return promptly.
});
track.addSink(sink);
try {
    // Keep the sink attached while receiving audio.
}
finally {
    track.removeSink(sink);
    sink.close();
}
```

A microphone track does not: WebRTC passes captured audio from the audio device
module straight to the senders, and the source of the track hands nothing to
sinks. Its audio after processing goes to the sink set with
`PeerConnectionFactory.setCapturedAudioSink`, which is what `LocalAudio.onAudio`
uses. An [`AudioRecorder`](/tools/audio/audio-recorder) captures a microphone
on its own, apart from any peer connection.

The adapter invokes the consumer on the thread that delivers the audio: a thread
of the `onAudio` subscription, or the audio callback thread of a sink. Keep work
there short. A subscription that falls behind loses the oldest audio beyond its
buffer, so a detector that must stay live passes a short one, as in
`onAudio(Duration.ofMillis(200), vad)`. Apply application-level timing to produce stable speech-start/speech-end
events, rather than toggling a UI or recording for every frame. Audio format
changes clear the adapter's partial frame and reset its detector.

## Related guides

- [Audio Processing](/guide/audio/audio-processing)
- [Audio Converter](/tools/audio/audio-converter)
- [Audio Recorder](/tools/audio/audio-recorder)
