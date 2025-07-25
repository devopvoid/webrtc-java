# Camera Video Track

This guide focuses on setting up a peer connection with camera video capture capabilities, which allows you to capture and stream video from connected cameras in your WebRTC connection.

## Camera Video Source Selection

To enable camera video capture, you need to:

1. List available video devices (cameras)
2. Create and configure a VideoDeviceSource
3. Create a video track with the camera source
4. Add the track to your peer connection

### Getting Available Cameras and Capabilities

Before configuring your camera source, you may want to check the supported resolutions and frame rates:

```java
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import java.util.List;

// Get all cameras
List<VideoDevice> cameras = MediaDevices.getVideoCaptureDevices();
if (cameras.isEmpty()) {
    System.out.println("No cameras found");
    return;
}

// Get the first camera
VideoDevice camera = cameras.get(0);
System.out.println("Camera: " + camera.getName());

// Get camera capabilities
List<VideoCaptureCapability> capabilities = MediaDevices.getVideoCaptureCapabilities(camera);

// Print camera capabilities
for (VideoCaptureCapability capability : capabilities) {
    System.out.println("  Resolution: " + capability.width + "x" + capability.height);
    System.out.println("  Frame Rate: " + capability.frameRate + " fps");
}
```

### Creating and Configuring a VideoDeviceSource

The `VideoDeviceSource` class allows you to capture video from a camera:

```java
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;

// Create a video device source
VideoDeviceSource videoSource = new VideoDeviceSource();

// Select a specific camera to capture from
VideoDevice camera = cameras.get(0); // Use the first available camera
videoSource.setVideoCaptureDevice(camera);

// Configure the capture capability (optional)
// Choose a capability from the list obtained earlier
VideoCaptureCapability capability = capabilities.get(0); // Use the first available capability
videoSource.setVideoCaptureCapability(capability);

// Start capturing
videoSource.start();
```

### Creating a Video Track with the Camera Source

Once you have configured your camera video source, you can create a video track:

```java
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.VideoTrack;

// Create a PeerConnectionFactory
PeerConnectionFactory factory = new PeerConnectionFactory();

// Create a video track with the camera source
VideoTrack videoTrack = factory.createVideoTrack("video0", videoSource);
```

### Adding the Track to a Peer Connection

Add the video track to your peer connection:

```java
import java.util.ArrayList;
import java.util.List;
import dev.onvoid.webrtc.RTCPeerConnection;

// Assuming you already have a configured RTCPeerConnection
RTCPeerConnection peerConnection = factory.createPeerConnection(config, observer);

// Add the track to the peer connection
List<String> streamIds = new ArrayList<>();
streamIds.add("stream1");
peerConnection.addTrack(videoTrack, streamIds);
```

## Additional Features

The `VideoDeviceSource` provides additional methods for controlling the camera capture:

### Resource Management

Always properly dispose of resources when done:

```java
// Dispose of resources when done
videoSource.stop();
videoSource.dispose();
```

### Handling Device Changes

If cameras might change during your application's lifecycle (e.g., cameras connecting or disconnecting), you should implement a device change listener:

```java
import dev.onvoid.webrtc.media.Device;
import dev.onvoid.webrtc.media.DeviceChangeListener;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.VideoDevice;

// Create a device change listener
DeviceChangeListener listener = new DeviceChangeListener() {
    @Override
    public void deviceConnected(Device device) {
        if (device instanceof VideoDevice) {
            System.out.println("Camera connected: " + device.getName());
            // You might want to update your UI or offer the new camera as an option
        }
    }

    @Override
    public void deviceDisconnected(Device device) {
        if (device instanceof VideoDevice) {
            System.out.println("Camera disconnected: " + device.getName());
            // Handle the case where the current camera was disconnected
        }
    }
};

// Register the listener
MediaDevices.addDeviceChangeListener(listener);

// ... later, when you're done listening for events
// Unregister the listener
MediaDevices.removeDeviceChangeListener(listener);
```

## Receiving Video Frames

Once you have set up your camera video source and peer connection, you'll likely want to receive and process the video frames. This section explains how to receive both local and remote video frames.

### Creating a VideoTrackSink

To receive video frames, you need to implement the `VideoTrackSink` interface and add it to a video track:

```java
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

// Create a class that implements VideoTrackSink
public class MyVideoSink implements VideoTrackSink {
    @Override
    public void onVideoFrame(VideoFrame frame) {
        // Process the video frame
        System.out.printf("Received frame: %dx%d%n",
                frame.buffer.getWidth(), frame.buffer.getHeight());
        
        // IMPORTANT: Always release the frame when done to prevent memory leaks
        frame.release();
    }
}
```

### Receiving Local Video Frames

To receive frames from your local camera:

```java
// Create your video sink
MyVideoSink localVideoSink = new MyVideoSink();

// Add the sink to your local video track
videoTrack.addSink(localVideoSink);

// Later, when you're done, remove the sink
videoTrack.removeSink(localVideoSink);
```

### Receiving Remote Video Frames

To receive frames from a remote peer, you need to add a sink to the remote video track when it's received. This is typically done in the `onTrack` method of your `PeerConnectionObserver`:

```java
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;

public class MyPeerConnectionObserver implements PeerConnectionObserver {
    private final MyVideoSink remoteVideoSink = new MyVideoSink();
    
    // Other methods omitted for brevity...
    
    @Override
    public void onTrack(RTCRtpTransceiver transceiver) {
        MediaStreamTrack track = transceiver.getReceiver().getTrack();
        String kind = track.getKind();
        
        if (kind.equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
            VideoTrack videoTrack = (VideoTrack) track;
            videoTrack.addSink(remoteVideoSink);
            System.out.println("Added sink to remote video track");
        }
    }
    
    // Make sure to clean up when done
    public void dispose() {
        // Remove sinks from any tracks they were added to
    }
}
```

### Processing Video Frames

When processing video frames, consider these important points:

1. **Frame Properties**: Each `VideoFrame` contains:
   - A `VideoFrameBuffer` with the actual pixel data
   - Width and height information
   - Rotation value
   - Timestamp

2. **Memory Management**: Always call `frame.release()` when you're done with a frame to prevent memory leaks.

3. **Performance Considerations**: Frame processing happens on the WebRTC thread, so keep your processing efficient. For heavy processing, consider:
   - Copying the frame data and processing it on a separate thread
   - Using a frame queue with a dedicated processing thread
   - Skipping frames if processing can't keep up with the frame rate

#### Converting VideoFrame to BufferedImage

To display or process video frames in Java applications, you often need to convert the `VideoFrame` to a `BufferedImage`. Here's how to do it:

```java
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

public void onVideoFrame(VideoFrame frame) {
    try {
        // Get frame dimensions
        VideoFrameBuffer frameBuffer = frame.buffer;
        int frameWidth = frameBuffer.getWidth();
        int frameHeight = frameBuffer.getHeight();
        
        // Create a BufferedImage with ABGR format (compatible with RGBA conversion)
        BufferedImage image = new BufferedImage(frameWidth, frameHeight, BufferedImage.TYPE_4BYTE_ABGR);
        
        // Get the underlying byte array from the BufferedImage
        byte[] imageBuffer = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        
        // Convert the frame buffer from I420 format to RGBA format
        VideoBufferConverter.convertFromI420(frameBuffer, imageBuffer, FourCC.RGBA);
        
        // Now you can use the BufferedImage for display or further processing
        // For example, you could display it in a Swing component:
        // myJLabel.setIcon(new ImageIcon(image));
        
    } catch (Exception e) {
        e.printStackTrace();
    } finally {
        // Always release the frame when done
        frame.release();
    }
}
```

This conversion process works as follows:

1. Create a `BufferedImage` with the same dimensions as the video frame, using `BufferedImage.TYPE_4BYTE_ABGR` format which is compatible with the RGBA format we'll convert to.

2. Get the underlying byte array from the BufferedImage using `((DataBufferByte) image.getRaster().getDataBuffer()).getData()`.

3. Use `VideoBufferConverter.convertFromI420()` to convert the frame buffer from I420 format (which is the internal format used by WebRTC) to RGBA format, storing the result directly in the BufferedImage's byte array.

4. The resulting BufferedImage can be used for display in Swing/JavaFX components or for further image processing.

#### Scaling Video Frames

Sometimes you may need to resize video frames to a different resolution, either to reduce processing requirements or to fit a specific display area. The `VideoFrameBuffer` interface provides a `cropAndScale` method that can be used for both cropping and scaling operations:

```java
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

public class ScalingVideoSink implements VideoTrackSink {
    private final int targetWidth;
    private final int targetHeight;
    
    public ScalingVideoSink(int targetWidth, int targetHeight) {
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
    }
    
    @Override
    public void onVideoFrame(VideoFrame frame) {
        try {
            // Get the original frame buffer
            VideoFrameBuffer originalBuffer = frame.buffer;
            int originalWidth = originalBuffer.getWidth();
            int originalHeight = originalBuffer.getHeight();
            
            // Scale the frame to the target resolution
            // For scaling only (no cropping), use the original dimensions for the crop region
            VideoFrameBuffer scaledBuffer = originalBuffer.cropAndScale(
                    0, 0,                          // No cropping from top-left (cropX, cropY)
                    originalWidth, originalHeight, // Use full frame width and height
                    targetWidth, targetHeight      // Scale to target dimensions
            );
            
            // Create a new frame with the scaled buffer
            VideoFrame scaledFrame = new VideoFrame(scaledBuffer, frame.rotation, frame.timestampNs);
            
            // Process the scaled frame
            System.out.printf("Scaled frame from %dx%d to %dx%d%n",
                    originalWidth, originalHeight, scaledBuffer.getWidth(), scaledBuffer.getHeight());
            
            // Don't forget to release both frames when done
            scaledFrame.release();
        } finally {
            // Always release the original frame
            frame.release();
        }
    }
}
```

This scaling process works as follows:

1. Get the original frame buffer and its dimensions.

2. Call `cropAndScale` on the original buffer with parameters:
   - `cropX = 0`, `cropY = 0`: Start from the top-left corner (no cropping)
   - `cropWidth = originalWidth`, `cropHeight = originalHeight`: Use the full frame (no cropping)
   - `scaleWidth = targetWidth`, `scaleHeight = targetHeight`: Scale to the desired dimensions

3. Create a new `VideoFrame` with the scaled buffer, preserving the original rotation and timestamp.

4. Process the scaled frame as needed.

5. Release both the scaled frame and the original frame to prevent memory leaks.

Note that scaling is a computationally expensive operation, especially for high-resolution frames. Consider the performance implications when choosing target dimensions and how frequently you scale frames.

## Best Practices

When implementing camera capture in your application, consider these best practices:

1. **Check for available cameras**: Always check if cameras are available before trying to use them.
2. **Handle no cameras gracefully**: Provide a fallback mechanism if no cameras are available.
3. **Let users select cameras**: If multiple cameras are available, let users choose which one to use.
4. **Select appropriate resolution**: Choose a resolution that balances quality and performance.
5. **Properly manage resources**: Always stop and dispose of video sources when they're no longer needed.
6. **Handle device changes**: Implement device change listeners to handle cameras being connected or disconnected.
7. **Consider privacy concerns**: In some applications, you may need to request permission to use the camera.

---

## Conclusion

This guide has demonstrated how to set up a WebRTC peer connection with camera video capture capabilities and how to receive and process both local and remote video frames.

Camera capture is essential for video conferencing applications, video chat, remote interviews, and any other applications where face-to-face communication is important.