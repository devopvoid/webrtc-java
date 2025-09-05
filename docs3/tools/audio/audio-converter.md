# Audio Converter

The `AudioConverter` remixes and resamples PCM audio frames to a target sample rate and channel count. It operates on 10 ms frames of 16‑bit PCM data and returns the number of output samples produced for each 10 ms frame.

API: `dev.onvoid.webrtc.media.audio.AudioConverter`

## Overview

- Input format: 16‑bit little‑endian PCM (`byte[]`)
- Frame duration: exactly 10 ms per call
- Channel remixing: up/down‑mix between mono/stereo (and other counts if supported by the native backend)
- Resampling: arbitrary input/output sample rates (e.g., 48 kHz → 16 kHz)
- Memory ownership: you provide both input and output buffers
- Native resources: must be released with `dispose()` when done

Key methods:
- `AudioConverter(int srcSampleRate, int srcChannels, int dstSampleRate, int dstChannels)` – configure the converter
- `int getTargetBufferSize()` – bytes required for the destination buffer for one 10 ms frame
- `int convert(byte[] src, byte[] dst)` – convert one 10 ms input frame into the destination buffer, returns number of samples written (per frame across all channels)
- `void dispose()` – free native resources

## Frame sizing

The converter operates on 10 ms frames. For a given sample rate and channel count, the number of samples per 10 ms is:

- Samples per channel = sampleRate / 100
- Total samples (all channels) = samples per channel × channels
- Bytes required = total samples × 2 (because 16‑bit PCM)

Examples:
- 48 kHz stereo input: samples = (48000 / 100) × 2 = 960 × 2 = 1920 samples → 3840 bytes
- 16 kHz mono output: samples = (16000 / 100) × 1 = 160 samples → 320 bytes

The method `getTargetBufferSize()` returns the exact number of bytes you need for the destination buffer for one 10 ms frame of the configured output format.

## Basic usage

```java
import dev.onvoid.webrtc.media.audio.AudioConverter;

// Convert 48 kHz stereo to 16 kHz mono
int srcSampleRate = 48000;
int srcChannels   = 2;
int dstSampleRate = 16000;
int dstChannels   = 1;

AudioConverter converter = new AudioConverter(srcSampleRate, srcChannels, dstSampleRate, dstChannels);

try {
    // Compute 10 ms frame sizes
    int srcSamplesPer10ms = (srcSampleRate / 100) * srcChannels; // 960 * 2 = 1920 samples
    int srcBytesPer10ms   = srcSamplesPer10ms * 2;                // 3840 bytes

    byte[] srcFrame = new byte[srcBytesPer10ms];

    // Destination buffer for one 10 ms frame of output
    byte[] dstFrame = new byte[converter.getTargetBufferSize()]; // e.g., 320 bytes for 16 kHz mono

    // Fill srcFrame from your capture or pipeline (exactly 10 ms of PCM 16‑bit data)
    // ...

    int outSamples = converter.convert(srcFrame, dstFrame);
    // outSamples equals (dstSampleRate / 100) * dstChannels, e.g., 160 for 16 kHz mono

    // Process/use dstFrame (contains 10 ms of resampled/remixed PCM 16‑bit data)
}
finally {
    converter.dispose();
}
```

## Continuous conversion loop

```java
AudioConverter converter = new AudioConverter(48000, 2, 48000, 1); // stereo to mono, same rate

try {
    int srcBytesPer10ms = (48000 / 100) * 2 /*channels*/ * 2 /*bytes*/; // 1920 * 2 = 3840
    byte[] srcFrame = new byte[srcBytesPer10ms];
    byte[] dstFrame = new byte[converter.getTargetBufferSize()];

    while (running) {
        // Read exactly 10 ms of input into srcFrame
        // ...

        converter.convert(srcFrame, dstFrame);

        // Write/queue dstFrame to the next stage (encoder, file, etc.)
    }
}
finally {
    converter.dispose();
}
```

## Error handling and caveats

- Frame length must be exactly 10 ms. If `src` has fewer samples than required, `convert` throws `IllegalArgumentException`.
- Ensure `dst` is at least `getTargetBufferSize()` bytes. Otherwise, `IllegalArgumentException` is thrown.
- Audio is assumed to be 16‑bit PCM. Do not pass float or 24‑bit samples.
- Always call `dispose()` to free native resources when the converter is no longer needed.

## Related guides

- [Audio Processing](/guide/audio/audio-processing)
- [Headless Audio](/guide/audio/headless-audio-device-module)
- [Voice Activity Detector](/tools/audio/voice-activity-detector)
