# Audio Recorder

The AudioRecorder is a small helper that captures audio from a selected input device and forwards PCM frames to your implementation of AudioSink. It manages a native AudioDeviceModule internally and provides idempotent start/stop.

API: `dev.onvoid.webrtc.media.audio.AudioRecorder`

## When to use it
- You want a simple way to record microphone input without wiring a full PeerConnection.
- You need raw 16‑bit PCM frames (10 ms) delivered to your code for analysis, file writing, or custom processing.

See also: [Audio Device Selection](../audio_devices.md), [Audio Processing](../audio_processing.md), [Custom Audio Source](../custom_audio_source.md).

## Key concepts
- Device selection: Provide an `AudioDevice` representing the input device (microphone) before starting.
- Data delivery: Implement `AudioSink` to receive recorded frames.
- Lifecycle: `start()` initializes and starts capture once; `stop()` halts and releases native resources.

## Basic usage

```java
import dev.onvoid.webrtc.media.audio.AudioRecorder;
import dev.onvoid.webrtc.media.audio.AudioSink;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;

public class MicRecorderExample {
    public static void main(String[] args) {
        // Pick a recording device (microphone). You can enumerate via MediaDevices.getAudioCaptureDevices()
        AudioDevice mic = ...

        // Implement a sink to receive 10 ms PCM frames
        AudioSink sink = (audioSamples, nSamples, nBytesPerSample, nChannels, samplesPerSec, totalDelayMS, clockDrift) -> {
            // audioSamples: little‑endian 16‑bit PCM
            // nSamples: total samples across all channels for this 10 ms frame
            // nBytesPerSample: typically 2 for 16‑bit
            // nChannels: number of channels (1 = mono, 2 = stereo)
            // samplesPerSec: sample rate (e.g., 48000)
            // totalDelayMS, clockDrift: diagnostics
            // TODO: write to file, analyzer, encoder, etc.
        };

        AudioRecorder recorder = new AudioRecorder();
        recorder.setAudioDevice(mic);
        recorder.setAudioSink(sink);

        recorder.start();
        // ... capture running ...
        recorder.stop();
    }
}
```

## Data format
- Frames are delivered every 10 ms as 16‑bit little‑endian PCM in a `byte[]`.
- `nSamples` refers to the number of samples across all channels within the 10 ms frame. Example: 48 kHz stereo → (48000/100)×2 = 960×2 = 1920 samples.

## Tips
- Apply echo cancellation, AGC, and noise suppression via the [Audio Processing](../audio_processing.md) guide if needed.
- If you need to resample or remix, use the [Audio Converter](../audio_converter.md).
- Device selection details and best practices are covered in [Audio Device Selection](../audio_devices.md).

## API reference
- `setAudioDevice(AudioDevice device)` – choose input device
- `setAudioSink(AudioSink sink)` – receive captured frames
- `start()` / `stop()` – control the capture lifecycle
