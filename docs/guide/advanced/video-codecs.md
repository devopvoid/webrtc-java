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

`HardwareVideoEncoderFactory` offers the codecs of `DefaultVideoEncoderFactory`, and H.265 where the hardware encodes it, and encodes on the GPU where the platform supports it:

```java
PeerConnectionFactory factory = PeerConnectionFactory.builder()
        .setVideoEncoderFactory(new HardwareVideoEncoderFactory())
        .build();
```

Which GPUs encode which codec, by platform and interface:

| Platform | Interface | GPUs | H.264 | H.265 | VP9 | AV1 |
|---|---|---|---|---|---|---|
| Windows | NVENC | NVIDIA | ✓ | ✓ | – | RTX 40 and newer |
| Windows | Media Foundation | AMD, Intel | ✓ | where the GPU encodes it | Intel, opt-in (1) | AMD RDNA3 and newer, Intel Arc |
| Linux | NVENC | NVIDIA | ✓ | ✓ | – | RTX 40 and newer |
| Linux | VA-API | Intel, AMD | ✓ | where the driver encodes it | Intel, also in temporal layers (2) | AMD RDNA3 and newer, also in temporal layers (2) |
| macOS | VideoToolbox | Apple | ✓ | Macs with a hardware HEVC encoder | – | – |
| | **Software fallback** | | OpenH264 | none (3) | libvpx | libaom |

1. VP9 through Media Foundation has not been tested on such a GPU yet and is off by default; turn it on with a system property before the factory is created: `System.setProperty("webrtc.hardware.vp9Encoder", "true");`
2. Single layer, `L1T2` or `L1T3`, with a rate control of each layer. AMD's AV1 encoder takes 320x128 and up; smaller streams are encoded by libaom.
3. H.265 has no software encoder. It is offered after the other codecs, only where the hardware encodes it, and a call uses it only if it is preferred (e.g. with `RTCRtpTransceiver.setCodecPreferences()`) and the other end decodes it. On macOS, VideoToolbox never falls back to its software HEVC encoder.

The hardware encoders take over formats the software encoders offer too, so encoding in hardware never changes what is negotiated:

| Codec | Formats encoded in hardware |
|---|---|
| H.264 | Constrained Baseline and Baseline, packetization mode 1 |
| H.265 | Main profile |
| VP9 | Profile 0 |
| AV1 | Profile 0 |

Hardware encoders produce a single layer, but for the VA-API encoders of AV1 and VP9, which encode temporal layers too (`L1T2` and `L1T3`), so that an SFU can forward a lower frame rate. A stream that asks for spatial layers, or for temporal layers from another hardware encoder, is encoded in software. When a hardware encoder fails to start, for example because the GPU has no encoder sessions left, or fails while encoding, the stream switches to the next encoder in line (e.g. from NVENC to Media Foundation on Windows), and finally to the software encoder, and continues with a key frame. On a machine without a hardware encoder, the factory encodes like `DefaultVideoEncoderFactory`.

Which encoder a stream uses shows in the `encoderImplementation` statistic of its `outbound-rtp` stats, e.g. `NVENC (NVIDIA GeForce RTX 4070)`, `MediaFoundation (AMDav1Encoder)`, `VA-API (Intel iHD driver ...)`, `VideoToolbox (H.265)`, `OpenH264` or `libaom`.

### Hardware Decoding

`HardwareVideoDecoderFactory` does the same for decoding:

```java
PeerConnectionFactory factory = PeerConnectionFactory.builder()
        .setVideoEncoderFactory(new HardwareVideoEncoderFactory())
        .setVideoDecoderFactory(new HardwareVideoDecoderFactory())
        .build();
```

Which GPUs decode which codec, by platform and interface; each where the GPU or driver decodes it:

| Platform | Interface | GPUs | H.264 | H.265 | VP9 | AV1 |
|---|---|---|---|---|---|---|
| Windows | Media Foundation (DXVA) | any | ✓ | with the HEVC Video Extensions (1) | with the VP9 Video Extensions (1) | with the AV1 Video Extension (1) |
| Linux | NVDEC | NVIDIA | ✓ | ✓ | ✓ | Ampere and newer |
| Linux | VA-API | Intel, AMD | ✓ | ✓ | ✓ | ✓ (2) |
| macOS | VideoToolbox | Apple | ✓ | Macs with a hardware HEVC decoder | Apple silicon | Apple M3 and newer |
| | **Software fallback** | | FFmpeg (4) | none (3) | libvpx | dav1d |

1. The *HEVC Video Extensions* and *VP9 Video Extensions* come from the Microsoft Store (or, for H.265, an HEVC decoder of the GPU vendor); the *AV1 Video Extension* is part of Windows 11. Without the VP9 one, VP9 is decoded in software; without an HEVC decoder, H.265 is not offered.
2. In 8 bit 4:2:0; see below for the streams that go to dav1d.
3. H.265 has no software decoder, so a stream the hardware cannot decode is not decoded.
4. On macOS, `DefaultVideoDecoderFactory` decodes H.264 through VideoToolbox already.

Some streams are decoded in software even where the GPU decodes their codec:

| Stream | Decoded by |
|---|---|
| Any codec with spatial layers (SVC) | the software decoder |
| H.264 High 4:4:4 Predictive (Windows, Linux) | FFmpeg |
| Interlaced H.264 (VA-API) | FFmpeg |
| VP9 profile 2, or below 64x64 or above 4096x4096 (macOS) | libvpx |
| AV1 with high bit depth or monochrome (macOS, VA-API) | dav1d |
| AV1 with film grain or superres (VA-API) | dav1d |

Temporal layers alone, as in `L1T3`, are a single spatial layer, and are decoded in hardware like any other stream.

A hardware decoder that fails, or turns out to decode in software, is replaced by the software decoder, which starts with the next key frame. Decoded frames are copied from GPU memory back to system memory, where WebRTC's frames are, so hardware decoding pays off mostly at high resolutions and with many streams; at low resolutions WebRTC's software decoders are about as cheap. On an Apple M2, for example, VP9 in hardware took a fraction of the processor time libvpx needs, while multi-threaded libvpx was about as fast on the clock.

Which decoder a stream uses shows in the `decoderImplementation` statistic of its `inbound-rtp` stats, e.g. `MediaFoundation (Microsoft H264 Video Decoder MFT)`, `MediaFoundation (AV1VideoExtension)`, `VideoToolbox (VP9)`, `VideoToolbox (H.265)`, `VideoToolbox (AV1)`, `NVDEC (NVIDIA GeForce RTX 4070)` or `VA-API (Mesa Gallium driver ...)`.

### Requirements

| Interface | Needs |
|---|---|
| NVENC | NVIDIA driver 522 or newer on Windows, 520 or newer on Linux |
| NVDEC | NVIDIA driver, which provides `libcuda.so.1` and `libnvcuvid.so.1` |
| VA-API | libva 2, a driver such as Intel's `intel-media-va-driver` (iHD) or Mesa's `mesa-va-drivers` for AMD that encodes H.264, and access to a render node in `/dev/dri` |
| Media Foundation | for decoding, the codec extensions above |

All of them are loaded at run time, so nothing needs to be installed on machines without them, and a machine without them encodes and decodes in software.

### Listing Hardware Codecs

Both hardware factories tell which of their codecs the GPU of the machine handles. `getHardwareCodecs()` returns those of `getSupportedCodecs()`, in the same order, that a hardware encoder or decoder takes; on macOS that includes H.264 through VideoToolbox. The other supported codecs are encoded or decoded in software. The hardware is probed once per process, which every factory then shares, and again only when one of the system properties above is set differently; a GPU, driver or codec extension installed while the process runs is seen by the next one:

```java
HardwareVideoEncoderFactory encoders = new HardwareVideoEncoderFactory();
List<VideoCodecInfo> hardware = encoders.getHardwareCodecs();

for (VideoCodecInfo codec : encoders.getSupportedCodecs()) {
    String where = hardware.contains(codec) ? "hardware" : "software";
    System.out.println(codec.getName() + " " + codec.getParameters() + ": " + where);
}
```

The list is per format: on Windows, for example, Media Foundation encoders take H.264 Constrained Baseline and Baseline with packetization mode 1, so the Main profile and packetization mode 0 are listed as software. A hardware codec is the one tried first, not a guarantee: a stream that falls back to software, as described above, or asks for what the hardware does not do, such as SVC, is encoded or decoded in software. The `powerEfficientEncoder` and `powerEfficientDecoder` statistics of the `outbound-rtp` and `inbound-rtp` stats tell what a running stream uses.

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

### H.265

The library is built with WebRTC's H.265 support: H.265 is negotiated with its SDP parameters (`profile-id`, `tier-flag`, `level-id`, `tx-mode`) and sent with the H.265 RTP payload format (RFC 7798). WebRTC has no H.265 encoder or decoder of its own, and this library adds none in software, so H.265 is offered only by a factory that brings one: the hardware factories on Macs that encode or decode it in hardware (see above), or one of your own, such as a hardware encoder reached through another library:

```java
Map<String, String> parameters = new HashMap<>();
parameters.put("profile-id", "1");     // Main
parameters.put("tier-flag", "0");
parameters.put("level-id", "93");      // level 3.1
parameters.put("tx-mode", "SRST");

codecs.add(new VideoCodecInfo("H265", parameters));
```

The encoder hands WebRTC each picture as an access unit in Annex B form, with the parameter sets (VPS, SPS and PPS) in every key frame, and the decoder receives them the same way.

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
- `getScalingSettings()`, `getEncoderInfo()`, `getResolutionBitrateLimits()`, `getImplementationName()` and `isHardwareEncoder()` have defaults and may be overridden. The default scaling settings use WebRTC's quantizer thresholds for VP8, VP9, H.264 and H.265 (which has those of H.264 until WebRTC has its own); a custom codec without a quantizer should return `ScalingSettings.OFF`.

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
- `HardwareVideoEncoderFactory`, `HardwareVideoDecoderFactory` — the built-in codecs, on the GPU where possible; `getHardwareCodecs()` lists those the GPU handles.
- `RTCRtpTransceiver.setCodecPreferences()` — chooses among the negotiated codecs.

For the full API, see the JavaDoc of the `dev.onvoid.webrtc.media.video.codec` package.
