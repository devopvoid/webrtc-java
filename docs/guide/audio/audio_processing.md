# Audio Processing

This guide explains how to use the audio processing capabilities provided by the `dev.onvoid.webrtc.media.audio.AudioProcessing` class. The library provides a collection of voice processing components designed for real-time communications software.

## Overview

The `AudioProcessing` class offers several audio processing features:

- **Echo Cancellation**: Removes echo from audio signals
- **Noise Suppression**: Reduces background noise
- **Gain Control**: Adjusts audio levels automatically
- **High-Pass Filtering**: Removes low-frequency noise

These features are particularly useful for VoIP applications, video conferencing, and other real-time communication systems.

## Two Approaches to Audio Processing

There are two main approaches to using audio processing in the library:

1. **Automatic Processing with PeerConnectionFactory**: Set a configured `AudioProcessing` instance to the `PeerConnectionFactory`. This is the recommended approach for most applications.
2. **Manual Processing**: Create an `AudioProcessing` instance and manually process audio streams. This gives you more control but requires more work.

### Automatic Processing with PeerConnectionFactory

The simplest way to use audio processing is to set a configured `AudioProcessing` instance to the `PeerConnectionFactory`:

```java
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.NoiseSuppression;

// Create and configure an AudioProcessing instance
AudioProcessing audioProcessing = new AudioProcessing();
AudioProcessingConfig config = new AudioProcessingConfig();

// Enable echo cancellation
config.echoCanceller.enabled = true;

// Enable noise suppression
config.noiseSuppression.enabled = true;
config.noiseSuppression.level = NoiseSuppression.Level.MODERATE;

// Apply the configuration
audioProcessing.applyConfig(config);

// Create a PeerConnectionFactory with the configured AudioProcessing
PeerConnectionFactory factory = new PeerConnectionFactory(audioProcessing);

// Now all audio processing will be handled automatically by the WebRTC framework
// ...

// Don't forget to dispose when done
factory.dispose();
audioProcessing.dispose();
```

With this approach, the WebRTC framework automatically applies the audio processing to all audio streams. You don't need to manually process audio data - the WebRTC framework handles it internally based on your configuration.

### Manual Processing

For more control, you can manually process audio streams:

```java
import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig;

// Create an AudioProcessing instance
AudioProcessing audioProcessing = new AudioProcessing();

try {
    // Configure audio processing
    AudioProcessingConfig config = new AudioProcessingConfig();
    
    audioProcessing.applyConfig(config);
    
    // Process audio streams
    // ...
} finally {
    // Always dispose when done to release native resources
    audioProcessing.dispose();
}
```

## Configuration

The `AudioProcessingConfig` class allows you to enable and configure various audio processing features:

### Echo Cancellation

Echo cancellation removes echo from audio signals, which is essential for full-duplex audio communication:

```java
AudioProcessingConfig config = new AudioProcessingConfig();

// Enable echo cancellation
config.echoCanceller.enabled = true;

// Enable high-pass filtering during echo cancellation
config.echoCanceller.enforceHighPassFiltering = true;
```

When echo cancellation is enabled, you should set the stream delay to help the echo canceller:

```java
// Set the delay between far-end and near-end audio in milliseconds
audioProcessing.setStreamDelayMs(70);
```

### Noise Suppression

Noise suppression reduces background noise in the audio signal:

```java
// Enable noise suppression
config.noiseSuppression.enabled = true;

// Set the level of noise suppression
// Options: LOW, MODERATE, HIGH, VERY_HIGH
config.noiseSuppression.level = NoiseSuppression.Level.MODERATE;
```

### Gain Control

Gain control adjusts the audio level automatically:

```java
// Enable gain control
config.gainControl.enabled = true;

// Configure fixed digital gain (in dB)
config.gainControl.fixedDigital.gainDb = 5.0f;

// Or configure adaptive digital gain
config.gainControl.adaptiveDigital.enabled = true;
config.gainControl.adaptiveDigital.headroomDb = 3.0f;
config.gainControl.adaptiveDigital.maxGainDb = 30.0f;
config.gainControl.adaptiveDigital.initialGainDb = 8.0f;
config.gainControl.adaptiveDigital.maxGainChangeDbPerSecond = 3.0f;
config.gainControl.adaptiveDigital.maxOutputNoiseLevelDbfs = -50.0f;
```

### High-Pass Filter

High-pass filtering removes low-frequency noise:

```java
// Enable high-pass filtering
config.highPassFilter.enabled = true;
```

## Processing Audio

The `AudioProcessing` class processes audio in 10ms chunks of linear PCM audio data. You need to configure the input and output formats using `AudioProcessingStreamConfig`:

```java
// Define input and output stream configurations
int inputSampleRate = 48000;  // 48 kHz
int inputChannels = 1;        // Mono
int outputSampleRate = 48000; // 48 kHz
int outputChannels = 1;       // Mono

AudioProcessingStreamConfig inputConfig = 
    new AudioProcessingStreamConfig(inputSampleRate, inputChannels);
AudioProcessingStreamConfig outputConfig = 
    new AudioProcessingStreamConfig(outputSampleRate, outputChannels);
```

### Calculate Buffer Size

Before processing, you need to calculate the appropriate buffer size:

```java
// Calculate buffer size for destination buffer
int bufferSize = audioProcessing.getTargetBufferSize(inputConfig, outputConfig);

// Create source and destination buffers
byte[] sourceBuffer = new byte[inputSampleRate / 100 * inputChannels * 2]; // 10ms of audio
byte[] destBuffer = new byte[bufferSize];
```

### Process Near-End Audio

Process audio captured from the local microphone:

```java
// Fill sourceBuffer with audio data from microphone
// ...

// Process the audio
int result = audioProcessing.processStream(
    sourceBuffer, inputConfig, outputConfig, destBuffer);

// Check result (0 means success)
if (result == 0) {
    // Use processed audio in destBuffer
    // ...
}
```

### Process Far-End Audio

For echo cancellation, you also need to process audio received from the remote end:

```java
// Fill sourceBuffer with audio data from remote participant
// ...

// Process the far-end audio
int result = audioProcessing.processReverseStream(
    sourceBuffer, inputConfig, outputConfig, destBuffer);

// Check result (0 means success)
if (result == 0) {
    // Use processed audio in destBuffer (usually for playback)
    // ...
}
```

## Format Conversion

The `AudioProcessing` class can also convert between different audio formats:

### Down-mixing (Stereo to Mono)

```java
// Configure for down-mixing
AudioProcessingStreamConfig stereoConfig = 
    new AudioProcessingStreamConfig(48000, 2); // Stereo input
AudioProcessingStreamConfig monoConfig = 
    new AudioProcessingStreamConfig(48000, 1); // Mono output

// Process with format conversion
audioProcessing.processStream(
    stereoBuffer, stereoConfig, monoConfig, monoDestBuffer);
```

### Up-mixing (Mono to Stereo)

```java
// Configure for up-mixing
AudioProcessingStreamConfig monoConfig = 
    new AudioProcessingStreamConfig(48000, 1); // Mono input
AudioProcessingStreamConfig stereoConfig = 
    new AudioProcessingStreamConfig(48000, 2); // Stereo output

// Process with format conversion
audioProcessing.processStream(
    monoBuffer, monoConfig, stereoConfig, stereoDestBuffer);
```

### Sample Rate Conversion

```java
// Configure for sample rate conversion
AudioProcessingStreamConfig highRateConfig = 
    new AudioProcessingStreamConfig(48000, 1); // 48 kHz
AudioProcessingStreamConfig lowRateConfig = 
    new AudioProcessingStreamConfig(16000, 1); // 16 kHz

// Process with sample rate conversion
audioProcessing.processStream(
    highRateBuffer, highRateConfig, lowRateConfig, lowRateDestBuffer);
```

## Statistics

The `AudioProcessing` class provides statistics about the audio processing performance:

```java
// Get statistics
AudioProcessingStats stats = audioProcessing.getStatistics();

// Echo cancellation statistics
System.out.println("Echo Return Loss: " + stats.echoReturnLoss + " dB");
System.out.println("Echo Return Loss Enhancement: " + stats.echoReturnLossEnhancement + " dB");
System.out.println("Divergent Filter Fraction: " + stats.divergentFilterFraction);

// Delay statistics
System.out.println("Current Delay: " + stats.delayMs + " ms");
System.out.println("Median Delay: " + stats.delayMedianMs + " ms");
System.out.println("Delay Standard Deviation: " + stats.delayStandardDeviationMs + " ms");

// Residual echo statistics
System.out.println("Residual Echo Likelihood: " + stats.residualEchoLikelihood);
System.out.println("Recent Max Residual Echo Likelihood: " + stats.residualEchoLikelihoodRecentMax);
```

These statistics are particularly useful for monitoring the performance of echo cancellation.

## Best Practices

1. **Always dispose**: Call `dispose()` when you're done with the `AudioProcessing` instance to free native resources.

2. **Configure before processing**: Apply your configuration before processing any audio for best results.

3. **Set stream delay**: For echo cancellation to work effectively, set the stream delay using `setStreamDelayMs()`.

4. **Process in 10ms chunks**: The audio processing is designed to work with 10ms chunks of audio.

5. **Monitor statistics**: Use the statistics to monitor the performance of echo cancellation and adjust settings if needed.

---

## Conclusion

The `AudioProcessing` class provides powerful audio processing capabilities for real-time communications. By properly configuring and using these features, you can significantly improve the audio quality in your applications.

Remember that audio processing is CPU-intensive, so consider the performance implications when enabling multiple features, especially on resource-constrained devices.