# Custom Audio Source

This guide explains how to use the `CustomAudioSource` class to push audio data from external sources directly to the WebRTC audio pipeline.

## Overview

The `CustomAudioSource` allows you to provide audio data from custom sources such as:
- Audio files
- Network streams
- Generated audio (tones, noise, etc.)
- Audio processing libraries
- Any other source of raw audio data

This is particularly useful when you need to:
- Stream pre-recorded audio
- Process audio before sending it
- Generate synthetic audio
- Integrate with external audio APIs

## Basic Usage

### Creating a Custom Audio Source

To use a custom audio source, you first need to create an instance:

```java
import dev.onvoid.webrtc.media.audio.CustomAudioSource;

// Create a new CustomAudioSource instance
CustomAudioSource audioSource = new CustomAudioSource();
```

### Creating an Audio Track

Once you have a custom audio source, you can create an audio track with it:

```java
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioTrack;

// Create a PeerConnectionFactory (you should already have this in your WebRTC setup)
PeerConnectionFactory factory = new PeerConnectionFactory();

// Create an audio track using the custom audio source
AudioTrack audioTrack = factory.createAudioTrack("audio-track-id", audioSource);
```

### Pushing Audio Data

The key feature of `CustomAudioSource` is the ability to push audio data directly to the WebRTC pipeline:

```java
// Parameters for the audio data
int bitsPerSample = 16;    // Common values: 8, 16, 32
int sampleRate = 48000;    // Common values: 8000, 16000, 44100, 48000
int channels = 2;          // 1 for mono, 2 for stereo
int frameCount = 480;      // For 10ms of audio at 48kHz

// Create a buffer for the audio data
// Size = frameCount * channels * (bitsPerSample / 8)
int bytesPerSample = bitsPerSample / 8;
byte[] audioData = new byte[frameCount * channels * bytesPerSample];

// Fill the buffer with your audio data
// ...

// Push the audio data to the WebRTC pipeline
audioSource.pushAudio(audioData, bitsPerSample, sampleRate, channels, frameCount);
```

## Audio Format Considerations

When pushing audio data, you need to consider the following parameters:

### Bits Per Sample
- **8-bit**: Lower quality, smaller data size
- **16-bit**: Standard quality for most applications
- **32-bit**: Higher quality, larger data size

### Sample Rate
- **8000 Hz**: Telephone quality
- **16000 Hz**: Good for speech
- **44100 Hz**: CD quality
- **48000 Hz**: Standard for digital audio workstations and professional audio

### Channels
- **1 (Mono)**: Single channel audio
- **2 (Stereo)**: Dual channel audio

### Frame Count
The number of frames depends on the desired buffer size and sample rate. For a 10ms buffer:
- At 8000 Hz: 80 frames
- At 16000 Hz: 160 frames
- At 44100 Hz: 441 frames
- At 48000 Hz: 480 frames

## Advanced Usage

### Continuous Audio Streaming

For continuous streaming, you'll typically push audio data in a separate thread:

```java
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AudioStreamer {
    private final CustomAudioSource audioSource;
    private final ScheduledExecutorService executor;
    private final int bitsPerSample = 16;
    private final int sampleRate = 48000;
    private final int channels = 2;
    private final int frameCount = 480; // 10ms at 48kHz
    
    public AudioStreamer(CustomAudioSource audioSource) {
        this.audioSource = audioSource;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }
    
    public void start() {
        // Schedule task to run every 10ms
        executor.scheduleAtFixedRate(this::pushNextAudioBuffer, 0, 10, TimeUnit.MILLISECONDS);
    }
    
    public void stop() {
        executor.shutdown();
    }
    
    private void pushNextAudioBuffer() {
        // Create and fill audio buffer
        int bytesPerSample = bitsPerSample / 8;
        byte[] audioData = new byte[frameCount * channels * bytesPerSample];
        
        // Fill audioData with your audio samples
        // ...
        
        // Push to WebRTC
        audioSource.pushAudio(audioData, bitsPerSample, sampleRate, channels, frameCount);
    }
}
```

## Integration with Audio Tracks

### Adding Sinks to Monitor Audio

You can add sinks to the audio track to monitor the audio data:

```java
import dev.onvoid.webrtc.media.audio.AudioTrackSink;

// Create a sink to monitor the audio data
AudioTrackSink monitorSink = (data, bitsPerSample, sampleRate, channels, frames) -> {
    System.out.println("Received audio: " + 
                      bitsPerSample + " bits, " + 
                      sampleRate + " Hz, " + 
                      channels + " channels, " + 
                      frames + " frames");
    
    // You can process or analyze the audio data here
};

// Add the sink to the audio track
audioTrack.addSink(monitorSink);

// When done, remove the sink
audioTrack.removeSink(monitorSink);
```

## Cleanup

When you're done with the custom audio source, make sure to clean up resources:

```java
// Dispose of the audio track
audioTrack.dispose();

// Dispose of the audio source to prevent memory leaks
audioSource.dispose();

// If you're using a scheduled executor for pushing audio
audioStreamer.stop();
```

---

## Conclusion

The `CustomAudioSource` provides a flexible way to integrate external audio sources with WebRTC. By understanding the audio format parameters and properly managing the audio data flow, you can create applications that use custom audio from virtually any source.

For more advanced audio processing options, consider exploring the audio processing APIs available in this documentation.