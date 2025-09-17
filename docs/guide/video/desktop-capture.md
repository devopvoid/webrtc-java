# Desktop Video

This guide focuses on setting up a peer connection with desktop video capture capabilities, which allows you to capture and stream content from your screens or application windows in your WebRTC connection.

## Desktop Video Source Selection

To enable desktop video capture, you need to:

1. List available desktop sources (screens and windows)
2. Create and configure a `VideoDesktopSource`
3. Create a video track with the desktop source
4. Add the track to your peer connection

### Listing Available Desktop Sources

The library provides classes to list available screens and windows:

```java
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;
import java.util.List;

// Get available screens
ScreenCapturer screenCapturer = new ScreenCapturer();
List<DesktopSource> screens = screenCapturer.getDesktopSources();
System.out.println("Available screens:");
for (DesktopSource screen : screens) {
    System.out.printf("  Screen: %s (ID: %d)%n", screen.title, screen.id);
}

// Get available windows
WindowCapturer windowCapturer = new WindowCapturer();
List<DesktopSource> windows = windowCapturer.getDesktopSources();
System.out.println("Available windows:");
for (DesktopSource window : windows) {
    System.out.printf("  Window: %s (ID: %d)%n", window.title, window.id);
}

// Clean up the capturers after use
screenCapturer.dispose();
windowCapturer.dispose();
```

### Creating and Configuring a VideoDesktopSource

The `VideoDesktopSource` class allows you to capture video from a desktop source:

```java
import dev.onvoid.webrtc.media.video.VideoDesktopSource;

// Create a desktop video source
VideoDesktopSource videoSource = new VideoDesktopSource();

// Configure the desktop video source
// Set frame rate (e.g., 30 fps)
videoSource.setFrameRate(30);

// Set maximum frame size (e.g., 1920x1080)
videoSource.setMaxFrameSize(1920, 1080);

// Select a specific source to capture
// For a screen (isWindow = false)
videoSource.setSourceId(screenId, false);
// OR for a window (isWindow = true)
videoSource.setSourceId(windowId, true);

// Start capturing
videoSource.start();
```

### Creating a Video Track with the Desktop Source

Once you have configured your desktop video source, you can create a video track:

```java
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.VideoTrack;

// Create a PeerConnectionFactory
PeerConnectionFactory factory = new PeerConnectionFactory();

// Create a video track with the desktop source
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

The `VideoDesktopSource` provides additional methods for controlling the desktop capture:

### Source Selection

You can change the capture source at runtime:

```java
// Switch to a different screen
videoSource.setSourceId(newScreenId, false);

// Switch to a window
videoSource.setSourceId(windowId, true);
```

### Capture Configuration

You can adjust capture settings:

```java
// Change frame rate
videoSource.setFrameRate(15);  // Set to 15 fps

// Change maximum resolution
videoSource.setMaxFrameSize(1280, 720);  // Set to 720p
```

### Resource Management

Always properly dispose of resources when done:

```java
// Dispose of resources when done
videoSource.stop();
videoSource.dispose();
```

### Handling Source Changes

If desktop sources might change during your application's lifecycle (e.g., new windows opening or screens connecting), you should periodically refresh the source list:

```java
// Refresh the list of available sources
ScreenCapturer screenCapturer = new ScreenCapturer();
List<DesktopSource> updatedScreens = screenCapturer.getDesktopSources();

WindowCapturer windowCapturer = new WindowCapturer();
List<DesktopSource> updatedWindows = windowCapturer.getDesktopSources();

// Clean up
screenCapturer.dispose();
windowCapturer.dispose();
```

## Conclusion

This guide has demonstrated how to set up a WebRTC peer connection with desktop video capture capabilities.
When implementing desktop capture in your application, remember to:

- Always dispose of resources properly to prevent memory leaks
- Periodically refresh source lists to handle dynamic changes in available screens and windows
- Consider the performance implications of higher resolutions and frame rates
- Handle potential permission requirements on different operating systems

Desktop capture is particularly useful for screen sharing applications, remote assistance tools, collaborative workspaces, and educational platforms where visual content sharing is essential.