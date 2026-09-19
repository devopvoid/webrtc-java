# Custom Video Source

This guide explains how to use the `CustomVideoSource` class to push video frames from external sources directly to the WebRTC video pipeline.

## Overview

The `CustomVideoSource` allows you to provide video frames from custom sources such as:
- Video files
- Network streams
- Generated video (patterns, animations, etc.)
- Video processing libraries
- Any other source of raw video frames

This is particularly useful when you need to:
- Stream pre-recorded video
- Process video before sending it
- Generate synthetic video
- Integrate with external video APIs

## Basic Usage

### Creating a Custom Video Source

To use a custom video source, you first need to create an instance:

```java
import dev.onvoid.webrtc.media.video.CustomVideoSource;

// Create a new CustomVideoSource instance
CustomVideoSource videoSource = new CustomVideoSource();
```

### Creating a Video Track

Once you have a custom video source, you can create a video track with it:

```java
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.VideoTrack;

// Create a PeerConnectionFactory (you should already have this in your WebRTC setup)
PeerConnectionFactory factory = new PeerConnectionFactory();

// Create a video track using the custom video source
VideoTrack videoTrack = factory.createVideoTrack("video-track-id", videoSource);
```

### Pushing Video Frames

The key feature of `CustomVideoSource` is the ability to push video frames directly to the WebRTC pipeline:

```java
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;

// Create a video frame with appropriate dimensions and format
int width = 640;
int height = 480;
NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);

// Fill the buffer with your video data
// ...

// Create a video frame with the buffer
VideoFrame frame = new VideoFrame(buffer, System.nanoTime());

// Push the frame to the WebRTC pipeline
videoSource.pushFrame(frame);

// Don't forget to release the frame when done
frame.dispose();
```

## Video Format Considerations

When pushing video frames, you need to consider the following:

### Resolution
- Common resolutions: 320x240, 640x480, 1280x720, 1920x1080
- Higher resolutions require more bandwidth and processing power

### Frame Rate
- Common frame rates: 15, 24, 30, 60 fps
- Higher frame rates provide smoother video but require more bandwidth

### Color Format
- WebRTC primarily uses I420 (YUV 4:2:0) format
- You may need to convert from other formats (RGB, RGBA, NV12, etc.)

## Advanced Usage

### Continuous Video Streaming

For continuous streaming, you'll typically push video frames in a separate thread:

```java
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VideoStreamer {
    private final CustomVideoSource videoSource;
    private final ScheduledExecutorService executor;
    private final int width = 640;
    private final int height = 480;
    private final int frameRate = 30;
    
    public VideoStreamer(CustomVideoSource videoSource) {
        this.videoSource = videoSource;
        this.executor = Executors.newSingleThreadScheduledExecutor();
    }
    
    public void start() {
        // Schedule task to run at the desired frame rate
        int periodMs = 1000 / frameRate;
        executor.scheduleAtFixedRate(this::pushNextVideoFrame, 0, periodMs, TimeUnit.MILLISECONDS);
    }
    
    public void stop() {
        executor.shutdown();
    }
    
    private void pushNextVideoFrame() {
        try {
            // Create a buffer for the frame
			NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
            
            // Fill buffer with your video data
            // ...
            
            // Create and push the frame
            VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
            videoSource.pushFrame(frame);
            
            // Release resources
            frame.dispose();
        }
		catch (Exception e) {
            e.printStackTrace();
        }
    }
}
```

### Keeping the Source's Own Timing

`pushFrame(VideoFrame)` treats a frame as captured at the moment of the call, so the timing that reaches the other peer is the timing of your pushing thread. For a live source such as a camera that is exactly right. For a source that has timing of its own, such as a video file, it is not: every hiccup of the executor above ends up in the stream, and audio pushed alongside drifts out of sync with the video.

Pass the capture time instead:

```java
// The clock capture timestamps are interpreted in.
long baseUs = SyncClock.currentTimeUs();

// For each frame, its own presentation time within the source.
long presentationUs = frameIndex * 1_000_000L / frameRate;

videoSource.pushFrame(frame, baseUs + presentationUs);
```

Map your source's timeline onto the clock once, when playback starts, and add each frame's presentation time to that base. The frame rate that reaches the encoder then follows your timestamps rather than the jitter of the thread.

::: warning
Frames must still be pushed in real time. A frame is encoded and sent when it arrives, so a timestamp in the future does not delay it; it only describes when the frame was meant to be shown.

Timestamps must also advance by at least one millisecond from frame to frame. WebRTC drops a frame whose capture time does not move forward.
:::

Push audio with timestamps from the same clock, as described in [Custom Audio Source](/guide/audio/custom-audio-source), and the two stay in sync on the receiving side.

## Integration with Video Tracks

### Adding Sinks to Monitor Video

You can add sinks to the video track to monitor the video frames:

```java
import dev.onvoid.webrtc.media.video.VideoTrackSink;

// Create a sink to monitor the video frames
VideoTrackSink monitorSink = frame -> {
    System.out.println("Received frame: " + 
                      frame.getBuffer().getWidth() + "x" + 
                      frame.getBuffer().getHeight() + ", " +
                      "rotation: " + frame.getRotation() + ", " +
                      "timestamp: " + frame.getTimestampUs() + "µs");
    
    // You can process or analyze the frame here
    // Note: Don't modify the frame directly
};

// Add the sink to the video track
videoTrack.addSink(monitorSink);

// When done, remove the sink
videoTrack.removeSink(monitorSink);
```

## Cleanup

When you're done with the custom video source, make sure to clean up resources:

```java
// Dispose of the video track
videoTrack.dispose();

// Dispose of the video source to prevent memory leaks
videoSource.dispose();

// If you're using a scheduled executor for pushing frames
videoStreamer.stop();
```

## Conclusion

The `CustomVideoSource` provides a flexible way to integrate external video sources with WebRTC. By understanding the video format parameters and properly managing the video frame flow, you can create applications that use custom video from virtually any source.

For more information on video processing and other WebRTC features, refer to the additional guides in the documentation.