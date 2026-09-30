# Video Codecs

A `PeerConnectionFactory` decides which video codecs it can send and receive through a video encoder factory and a video decoder factory. Unless told otherwise it uses the codecs built into the library. This guide explains how to replace or extend them: to add a codec WebRTC does not have, to use a hardware encoder reached through another library, or to limit the codecs a factory offers.

The API lives in the `dev.onvoid.webrtc.media.video.codec` package.

## Built-in Codecs

`DefaultVideoEncoderFactory` and `DefaultVideoDecoderFactory` provide the codecs built into the library. On Windows and Linux these are VP8, VP9, AV1 and H.264 in software; on macOS they are those of WebRTC's default factories, with H.264 through VideoToolbox. Ask a factory for the exact list:

```java
for (VideoCodecInfo codec : new DefaultVideoEncoderFactory().getSupportedCodecs()) {
    System.out.println(codec.getName() + " " + codec.getParameters());
}
```

### Hardware Encoding

`HardwareVideoEncoderFactory` offers the same codecs as `DefaultVideoEncoderFactory`, but encodes on the GPU where the platform supports it:

```java
PeerConnectionFactory factory = PeerConnectionFactory.builder()
        .setVideoEncoderFactory(new HardwareVideoEncoderFactory())
        .build();
```

| Platform | Hardware encoding |
|---|---|
| Windows | H.264 through the Media Foundation encoder of the GPU driver (NVIDIA, AMD, Intel) |
| macOS | H.264 through VideoToolbox, as with `DefaultVideoEncoderFactory` |
| Linux | Not yet; encoding is in software |

On Windows, the hardware encoder takes over H.264 Constrained Baseline and Baseline with packetization mode 1, formats the software encoder offers too, so encoding in hardware never changes what is negotiated. When the hardware encoder fails to start, for example because the GPU has no encoder sessions left, or fails while encoding, the stream switches to the software encoder and continues with a key frame. On a machine without a hardware encoder, the factory encodes like `DefaultVideoEncoderFactory`.

Which encoder a stream uses shows in the `encoderImplementation` statistic of its `outbound-rtp` stats, e.g. `MediaFoundation (AMDh264Encoder)` or `OpenH264`.

### Native Codecs

The encoders and decoders these factories create are `NativeVideoEncoder`s and `NativeVideoDecoder`s. They are placeholders that make WebRTC create the built-in codec, which then runs entirely inside WebRTC, so their methods are not to be called from Java.

## Setting the Factories

The codec factories are set with `PeerConnectionFactory.builder()`, which also takes everything the constructors do:

```java
PeerConnectionFactory factory = PeerConnectionFactory.builder()
        .setAudioDeviceModule(audioModule)
        .setVideoEncoderFactory(new MyEncoderFactory())
        .setVideoDecoderFactory(new MyDecoderFactory())
        .build();
```

A factory that is not set is the built-in one. The supported codecs are asked for once, when the `PeerConnectionFactory` is built; an exception thrown there fails `build()`. Encoders and decoders are created later, from WebRTC threads, one per stream.

Both ends have to agree on a codec, so a codec added on the sending side also has to be offered by the decoder factory of the receiving side.

## Adding a Codec

A factory of one's own usually hands out its own codec next to the built-in ones, by delegating to a default factory:

```java
class MyEncoderFactory implements VideoEncoderFactory {

    private final DefaultVideoEncoderFactory builtIn = new DefaultVideoEncoderFactory();

    @Override
    public List<VideoCodecInfo> getSupportedCodecs() {
        List<VideoCodecInfo> codecs = new ArrayList<>();
        codecs.add(new VideoCodecInfo("X-MYCODEC"));
        codecs.addAll(builtIn.getSupportedCodecs());
        return codecs;
    }

    @Override
    public VideoEncoder createEncoder(VideoCodecInfo info) {
        if (info.getName().equalsIgnoreCase("X-MYCODEC")) {
            return new MyEncoder();
        }
        return builtIn.createEncoder(info);
    }
}
```

The list is in order of preference. A codec name WebRTC does not know, such as `X-MYCODEC`, is negotiated like any other and sent with the generic RTP packetization. `RTCRtpTransceiver.setCodecPreferences()` picks among the negotiated codecs per transceiver.

## Implementing an Encoder

WebRTC calls `initEncode()`, then `encode()` for each frame and `setRates()` whenever the target bitrate changes, and finally `release()`. It may initialize a released encoder again, possibly with another frame size. These calls come from one WebRTC thread at a time.

```java
class MyEncoder implements VideoEncoder {

    private Callback callback;

    @Override
    public VideoCodecStatus initEncode(Settings settings, Callback callback) {
        this.callback = callback;
        // Set up the encoder for settings.width x settings.height at
        // settings.startBitrateKbps.
        return VideoCodecStatus.OK;
    }

    @Override
    public VideoCodecStatus encode(VideoFrame frame, EncodeInfo info) {
        I420Buffer pixels = frame.buffer.toI420();
        ByteBuffer payload = encodeSomehow(pixels, info.isKeyFrameRequested());

        callback.onEncodedFrame(EncodedImage.builder()
                .setBuffer(payload)
                .setEncodedWidth(pixels.getWidth())
                .setEncodedHeight(pixels.getHeight())
                .setCaptureTimeNs(frame.timestampNs)
                .setFrameType(info.isKeyFrameRequested()
                        ? EncodedImage.FrameType.KEY
                        : EncodedImage.FrameType.DELTA)
                .build());

        return VideoCodecStatus.OK;
    }

    @Override
    public VideoCodecStatus setRates(RateControlParameters parameters) {
        // Aim for parameters.bitrate.getSum() bps at parameters.framerateFps.
        return VideoCodecStatus.OK;
    }

    @Override
    public VideoCodecStatus release() {
        return VideoCodecStatus.OK;
    }
}
```

Things to keep in mind:

- The capture time of an `EncodedImage` must be the `timestampNs` of the frame it encodes. That is how WebRTC matches output to input; an image with any other capture time is dropped.
- The frame passed to `encode()` is valid only until the method returns. An encoder that works asynchronously calls `frame.retain()` and later `frame.release()`, and hands its output to the callback from its own thread, in encoding order. The pixels must not be changed, since other consumers may share them.
- `onEncodedFrame()` copies the payload, so the buffer may be reused once it returns. Frames handed over after `release()` are ignored.
- `getScalingSettings()`, `getEncoderInfo()`, `getResolutionBitrateLimits()`, `getImplementationName()` and `isHardwareEncoder()` have defaults and may be overridden. The default scaling settings use WebRTC's quantizer thresholds for VP8, VP9 and H.264; a custom codec without a quantizer should return `ScalingSettings.OFF`.

## Implementing a Decoder

A decoder follows the same pattern with `initDecode()`, `decode()` and `release()`:

```java
class MyDecoder implements VideoDecoder {

    private Callback callback;

    @Override
    public VideoCodecStatus initDecode(Settings settings, Callback callback) {
        this.callback = callback;
        return VideoCodecStatus.OK;
    }

    @Override
    public VideoCodecStatus decode(EncodedImage image) {
        NativeI420Buffer buffer = decodeSomehow(image.getBuffer());
        VideoFrame frame = new VideoFrame(buffer, image.getCaptureTimeNs());

        callback.onDecodedFrame(frame, null, null);
        frame.release();

        return VideoCodecStatus.OK;
    }

    @Override
    public VideoCodecStatus release() {
        return VideoCodecStatus.OK;
    }
}
```

- The image and its buffer are read-only and valid only until `decode()` returns; copy the data to keep it longer.
- A decoded frame must carry the capture time of the image it was decoded from as its timestamp.
- `onDecodedFrame()` takes a reference of its own, so the caller still releases the frame it created. Frame buffers other than `NativeI420Buffer` are copied, and have to provide direct byte buffers.

## Errors

Whatever an encoder, decoder or factory method throws is logged and treated as `VideoCodecStatus.ERROR`, and a factory that throws from `createEncoder()` or `createDecoder()` creates no codec for that stream. On an error other than `FALLBACK_SOFTWARE` and `UNINITIALIZED`, WebRTC releases the codec and initializes it again.

## Related API

- `PeerConnectionFactory.Builder` — sets the video encoder and decoder factories.
- `VideoEncoderFactory`, `VideoDecoderFactory` — create the codecs of a factory.
- `VideoEncoder`, `VideoDecoder`, `EncodedImage` — codecs implemented in Java.
- `DefaultVideoEncoderFactory`, `DefaultVideoDecoderFactory` — the built-in codecs.
- `HardwareVideoEncoderFactory` — the built-in encoders, on the GPU where possible.
- `RTCRtpTransceiver.setCodecPreferences()` — chooses among the negotiated codecs.

For the full API, see the JavaDoc of the `dev.onvoid.webrtc.media.video.codec` package.
