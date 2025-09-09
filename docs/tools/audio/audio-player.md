# Audio Player

The AudioPlayer is a small helper that plays audio using a selected output device by pulling frames from your implementation of AudioSource. It manages a native AudioDeviceModule internally and provides idempotent start/stop.

API: `dev.onvoid.webrtc.media.audio.AudioPlayer`

## When to use it
- You want to render raw PCM audio (generated or decoded by your app) to an OS output device.
- You need a simple, high‑level start/stop wrapper around WebRTC’s audio playout.

See also: [Audio Device Selection](/guide/audio/audio-devices), [Custom Audio Source](/guide/audio/custom-audio-source), [Headless Audio](/guide/audio/headless-audio).

## Key concepts
- Device selection: Provide an `AudioDevice` representing the output device (speaker) before starting.
- Data supply: Implement `AudioSource` to provide 10 ms PCM frames on demand.
- Lifecycle: `start()` initializes output and begins pulling; `stop()` halts playout and releases resources.

## Basic usage

```java
import dev.onvoid.webrtc.media.audio.AudioPlayer;
import dev.onvoid.webrtc.media.audio.AudioSource;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;

import java.nio.ByteBuffer;

public class TonePlayerExample {

    public static void main(String[] args) {
        // Choose a playout device (speaker). Enumerate via a temporary ADM.
        AudioDeviceModule adm = new AudioDeviceModule();
        AudioDevice speaker = adm.getPlayoutDevices().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No playout device found"));
        adm.dispose();

        // Provide 10 ms frames of PCM 16‑bit audio. This example generates a sine tone.
        final int sampleRate = 48000;
        final int channels = 2;
        final int bytesPerSample = channels * 2; // 16‑bit
        final double frequency = 440.0; // A4
        final double twoPiFDivFs = 2 * Math.PI * frequency / sampleRate;
        final int samplesPer10msPerChannel = sampleRate / 100; // 480 samples/channel
        final int totalSamplesPer10ms = samplesPer10msPerChannel * channels; // e.g., 960 samples
        final double[] phase = new double[] {0.0};

        AudioSource source = (audioSamples, nSamples, nBytesPerSample, nChannels, samplesPerSec) -> {
            // Ensure caller requested matches our configuration
            if (nBytesPerSample != bytesPerSample || nChannels != channels || samplesPerSec != sampleRate) {
                // Fill silence if formats mismatch
                java.util.Arrays.fill(audioSamples, (byte) 0);
                return totalSamplesPer10ms;
            }

            // Generate interleaved stereo sine wave
            int idx = 0;
            for (int i = 0; i < samplesPer10msPerChannel; i++) {
                short s = (short) (Math.sin(phase[0]) * 32767);
                // left
                audioSamples[idx++] = (byte) (s & 0xFF);
                audioSamples[idx++] = (byte) ((s >> 8) & 0xFF);
                // right
                audioSamples[idx++] = (byte) (s & 0xFF);
                audioSamples[idx++] = (byte) ((s >> 8) & 0xFF);

                phase[0] += twoPiFDivFs;

                if (phase[0] > Math.PI * 2) {
                    phase[0] -= Math.PI * 2;
                }
            }
            return totalSamplesPer10ms; // number of samples written across all channels
        };

        AudioPlayer player = new AudioPlayer();
        player.setAudioDevice(speaker);
        player.setAudioSource(source);

        player.start();
        // ... playout running ...
        player.stop();
    }
}
```

## Data format
- The player requests 10 ms frames as 16‑bit little‑endian PCM via `AudioSource#onPlaybackData`.
- Return value must be the number of samples written across all channels for that 10 ms frame.

## Tips
- If your synthesis/decoder operates at a different rate or channel layout, convert using the [Audio Converter](/tools/audio/audio-converter) before writing into the output buffer.

## API reference
- `setAudioDevice(AudioDevice device)` – choose output device
- `setAudioSource(AudioSource source)` – provide playout frames
- `start()` / `stop()` – control the playout lifecycle
