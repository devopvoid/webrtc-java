# Voice Activity Detector

The Voice Activity Detector (VAD) helps you determine when speech is present in an audio stream. It analyzes short chunks of PCM audio and returns the probability that the chunk contains voice.

This can be used to:
- Drive push-to-talk or auto-mute logic
- Skip encoding/sending silence to save bandwidth
- Trigger UI indicators when the user is speaking

API: `dev.onvoid.webrtc.media.audio.VoiceActivityDetector`

## Overview

`VoiceActivityDetector` exposes a minimal API:
- `process(byte[] audio, int samplesPerChannel, int sampleRate)`: Analyze one audio frame.
- `getLastVoiceProbability()`: Retrieve the probability (0.0..1.0) that the last processed frame contained voice.
- `dispose()`: Release native resources. Always call this when done.

Internally, VAD uses a native implementation optimized for real-time analysis. The class itself does not perform resampling or channel mixing, so provide audio matching the given `sampleRate` and expected format.

## Audio format expectations

- PCM signed 16-bit little-endian (typical Java byte[] from microphone capture via this library)
- Mono is recommended. If you have stereo, downmix to mono before calling `process` or pass samples-per-channel accordingly
- Frame size: commonly 10 ms per call (e.g., 160 samples at 16 kHz for 10 ms)
- Supported sample rates: 8 kHz, 16 kHz, 32 kHz, 48 kHz (use one of these for best results)

## Basic usage

```java
import dev.onvoid.webrtc.media.audio.VoiceActivityDetector;

// Create the detector
VoiceActivityDetector vad = new VoiceActivityDetector();

try {
    // Example parameters
    int sampleRate = 16000;                 // 16 kHz
    int frameMs = 10;                       // 10 ms frames
    int samplesPerChannel = sampleRate * frameMs / 1000; // 160 samples

    // audioFrame must contain 16-bit PCM data for one frame (mono)
    byte[] audioFrame = new byte[samplesPerChannel * 2]; // 2 bytes per sample

    // Fill audioFrame from your audio source here
    // ...

    // Analyze frame
    vad.process(audioFrame, samplesPerChannel, sampleRate);

    // Query probability of voice in the last frame
    float prob = vad.getLastVoiceProbability(); // 0.0 .. 1.0

    boolean isSpeaking = prob >= 0.5f; // choose a threshold that works for your app

}
finally {
    // Always release resources
    vad.dispose();
}
```

## Continuous processing loop

```java
VoiceActivityDetector vad = new VoiceActivityDetector();

try {
    int sampleRate = 16000;
    int frameMs = 10;
    int samplesPerChannel = sampleRate * frameMs / 1000; // 160 samples
    byte[] audioFrame = new byte[samplesPerChannel * 2];

    while (running) {
        // Read PCM frame from your capture pipeline into audioFrame
        // ...

        vad.process(audioFrame, samplesPerChannel, sampleRate);
        float prob = vad.getLastVoiceProbability();

        if (prob > 0.8f) {
            // High confidence of speech
            // e.g., enable VU meter, unmute, or mark active speaker
        }
        else {
            // Likely silence or noise
        }
    }
}
finally {
    vad.dispose();
}
```

## Tips and best practices

- Threshold selection: Start with 0.5–0.8 and tune for your environment.
- Frame size consistency: Use a consistent frame duration and sample rate.
- Resource management: VAD holds native resources; ensure `dispose()` is called.
- Preprocessing: Consider using `AudioProcessing` (noise suppression, gain control) before VAD for improved robustness in noisy environments. See the Audio Processing guide.

## Related guides

- [Audio Processing](guide/audio_processing.md)