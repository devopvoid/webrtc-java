# Audio Codecs

A `PeerConnectionFactory` decides which audio codecs it can send and receive through an audio encoder factory and an audio decoder factory. Unless told otherwise it uses all codecs built into WebRTC. This guide explains how to limit them, for example to offer only Opus, or to put a codec first that a remote endpoint expects.

The API lives in the `dev.onvoid.webrtc.media.audio.codec` package.

## Built-in Codecs

`BuiltinAudioEncoderFactory` and `BuiltinAudioDecoderFactory` provide WebRTC's audio codecs: Opus, G722, PCMU and PCMA. Ask a factory for the exact list:

```java
for (AudioCodecInfo codec : new BuiltinAudioEncoderFactory().getSupportedCodecs()) {
    System.out.println(codec.getName() + "/" + codec.getClockRate() + "/" + codec.getChannels()
            + " " + codec.getParameters());
}
```

## Limiting and Ordering the Codecs

Pass codec names to a factory to keep only those codecs, in the given order of preference. Names are compared ignoring case, and a name that is not a built-in codec throws an `IllegalArgumentException` that lists the valid ones:

```java
PeerConnectionFactory factory = PeerConnectionFactory.builder()
        .setAudioEncoderFactory(new BuiltinAudioEncoderFactory("opus"))
        .setAudioDecoderFactory(new BuiltinAudioDecoderFactory("opus", "PCMU"))
        .build();
```

The encoder factory decides what the factory can send and in which order its offers list the codecs; the decoder factory decides what it can receive. Without names, or without a factory set, all built-in codecs are used.

WebRTC adds the auxiliary codecs by itself, depending on the selected ones:

| Codec | Added when |
|---|---|
| `telephone-event` | A selected codec has a clock rate of 8000 or 48000 Hz (Opus, G722, PCMU, PCMA all do), so DTMF keeps working |
| `CN` | A selected codec runs at 8000 Hz and allows comfort noise |
| `red` | Opus is selected |

`getSupportedCodecs()` does not list them; `PeerConnectionFactory.getRtpSenderCapabilities(MediaType.AUDIO)` does.

::: tip
To change the codec order of a single connection rather than of the whole factory, use `RTCRtpTransceiver.setCodecPreferences()`. A factory's codec list is the set those preferences choose from.
:::

## Related API

- `PeerConnectionFactory.Builder.setAudioEncoderFactory()`, `setAudioDecoderFactory()` — set the factories.
- `BuiltinAudioEncoderFactory`, `BuiltinAudioDecoderFactory` — WebRTC's codecs, optionally limited.
- `AudioCodecInfo` — a codec's name, clock rate, channels and format parameters.
- [Video Codecs](/guide/advanced/video-codecs) — the same for video, including codecs written in Java.
