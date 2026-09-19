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

::: warning One kind of audio input per factory
A factory sends audio either from its `AudioDeviceModule`, which is what tracks created with `createAudioSource(AudioOptions)` send, or from audio you push. WebRTC feeds device-captured audio into **every** audio sender of a factory, so a sender that is fed both ways is fed by two threads at once. That is a race inside WebRTC, and it aborts the whole process:

```
Fatal error in: ../../audio/audio_send_stream.cc
Check failed: !race_checker.RaceDetected()
```

The factory therefore commits to the kind used first and throws an `IllegalStateException` if you ask for the other. The same applies to a track received from a remote peer and forwarded on, since its audio is pushed as well. If you need both, create a second `PeerConnectionFactory`.

While a factory sends pushed audio it never opens the recording device, so no microphone is used. Playout of received audio is unaffected.
:::

### Pushing Audio Data

The key feature of `CustomAudioSource` is the ability to push audio data directly to the WebRTC pipeline:

```java
// Parameters for the audio data
int bitsPerSample = 16;    // Must be 16; WebRTC reads the samples as 16-bit PCM
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

The call checks its arguments and throws `IllegalArgumentException` rather than letting a bad chunk reach WebRTC, where it would read past your array or abort the process:

- Samples must be **16-bit** signed PCM in the platform byte order, with the channels interleaved.
- The array must hold at least `frameCount * channels * 2` bytes. A longer array is fine; only that many bytes are read.
- One call carries one chunk, and **10 ms** is the size WebRTC works with. A chunk may hold at most `CustomAudioSource.MAX_SAMPLES_PER_PUSH` samples counting every channel, so push in small chunks rather than handing over a whole file or a growing buffer.
- `frameCount` counts frames, not samples and not bytes. A frame holds one sample per channel, so 10 ms at 48 kHz is 480 frames whether it is mono or stereo.

::: warning Push from one thread
The audio is handed to the track's senders on the thread that calls `pushAudio`, so call it from a single thread. A scheduled executor with one thread, as shown below, is the simplest way to do that.
:::

## Audio Format Considerations

When pushing audio data, you need to consider the following parameters:

### Bits Per Sample
- **16-bit**: the only width WebRTC accepts. Convert audio of any other width before pushing it.

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

## Audio processing

Pushed audio goes straight to the track's senders and does **not** pass through WebRTC's audio processing module. Echo cancellation, noise suppression and gain control apply to device-captured audio only, so they have no effect on a `CustomAudioSource`. Apply any processing you need before you push.

The standalone [`AudioProcessing`](/guide/audio/audio-processing) class wraps the same processing module, so you can run each chunk through it right before `pushAudio`. It takes the same 10 ms frames of 16-bit PCM that `pushAudio` takes, so no reformatting is needed in between.

### Noise suppression and gain control

```java
int sampleRate = 48000;
int channels = 1;
int frameCount = sampleRate / 100; // 10 ms

AudioProcessingConfig config = new AudioProcessingConfig();
config.noiseSuppression.enabled = true;
config.gainControllerDigital.enabled = true;
config.gainControllerDigital.adaptiveDigital.enabled = true;

AudioProcessing processing = new AudioProcessing();
processing.applyConfig(config);

// Input and output use the same format here; the module can also resample or
// down-mix between the two, see the audio processing guide.
AudioProcessingStreamConfig format = new AudioProcessingStreamConfig(sampleRate, channels);

byte[] raw = new byte[frameCount * channels * 2];
byte[] processed = new byte[processing.getTargetBufferSize(format, format)];

// On the single push thread, once per 10 ms:
fillWithAudio(raw); // your source
int result = processing.processStream(raw, format, format, processed);

if (result == 0) {
    audioSource.pushAudio(processed, 16, sampleRate, channels, frameCount);
}
```

Dispose the `AudioProcessing` instance together with the source once you stop pushing.

### Echo cancellation

Echo cancellation needs the far-end signal as a reference. Device capture gets it for free, since WebRTC feeds every rendered frame back into the module itself. For a `CustomAudioSource` you provide it: feed the audio you play out through `processReverseStream`, and tell the module how far apart the two streams are in time.

```java
config.echoCanceller.enabled = true;
processing.applyConfig(config);

// The delay between a far-end frame reaching processReverseStream and the
// echo of it reaching processStream. Measure it for your setup; a value that
// is roughly right is enough for the canceller to lock on.
processing.setStreamDelayMs(50);

// Feed every far-end frame you play out. A sink on the received track gets
// them in the format below. WebRTC calls it on its own thread, which is fine:
// processStream and processReverseStream may run concurrently.
remoteAudioTrack.addSink((data, bitsPerSample, rate, ch, frames) -> {
    AudioProcessingStreamConfig farEnd = new AudioProcessingStreamConfig(rate, ch);
    byte[] reverse = new byte[processing.getTargetBufferSize(farEnd, farEnd)];

    processing.processReverseStream(data, farEnd, farEnd, reverse);
});
```

The canceller can only remove echo of audio that actually went through `processReverseStream`. If your application plays out the received audio through a path the sink does not see, or plays other sounds alongside it, that audio comes back uncancelled.

::: tip
If a factory sends only pushed audio, the processing module WebRTC creates for that factory does no work. Enabling its features through `AudioOptions` or the factory's `AudioProcessing` changes nothing for a `CustomAudioSource`; only the instance you apply yourself does.
:::

## Conclusion

The `CustomAudioSource` provides a flexible way to integrate external audio sources with WebRTC. By understanding the audio format parameters and properly managing the audio data flow, you can create applications that use custom audio from virtually any source.

For more advanced audio processing options, consider exploring the audio processing APIs available in this documentation.