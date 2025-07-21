# WebRTC Java - Desktop Video Track Selection Guide

This README explains how to set up a WebRTC peer connection with desktop video capture capabilities.

## Overview

This guide focuses on setting up a peer connection with desktop video capture capabilities, which allows you to capture and stream content from your screens or application windows in your WebRTC connection.

## Desktop Video Source Selection

To enable desktop video capture, you need to:

1. List available desktop sources (screens and windows)
2. Create and configure a VideoDesktopSource
3. Create a video track with the desktop source
4. Add the track to your peer connection

### Listing Available Desktop Sources

The library provides classes to list available screens and windows:

<pre>
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;
import java.util.List;

// Get available screens
ScreenCapturer screenCapturer = new ScreenCapturer();
List&lt;DesktopSource&gt; screens = screenCapturer.getDesktopSources();
System.out.println("Available screens:");
for (DesktopSource screen : screens) {
    System.out.printf("  Screen: %s (ID: %d)%n", screen.title, screen.id);
}

// Get available windows
WindowCapturer windowCapturer = new WindowCapturer();
List&lt;DesktopSource&gt; windows = windowCapturer.getDesktopSources();
System.out.println("Available windows:");
for (DesktopSource window : windows) {
    System.out.printf("  Window: %s (ID: %d)%n", window.title, window.id);
}

// Clean up the capturers after use
screenCapturer.dispose();
windowCapturer.dispose();
</pre>

### Creating and Configuring a VideoDesktopSource

The `VideoDesktopSource` class allows you to capture video from a desktop source:

<pre>
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
</pre>

### Creating a Video Track with the Desktop Source

Once you have configured your desktop video source, you can create a video track:

<pre>
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.VideoTrack;

// Create a PeerConnectionFactory
PeerConnectionFactory factory = new PeerConnectionFactory();

// Create a video track with the desktop source
VideoTrack videoTrack = factory.createVideoTrack("video0", videoSource);
</pre>

### Adding the Track to a Peer Connection

Add the video track to your peer connection:

<pre>
import java.util.ArrayList;
import java.util.List;
import dev.onvoid.webrtc.RTCPeerConnection;

// Assuming you already have a configured RTCPeerConnection
RTCPeerConnection peerConnection = factory.createPeerConnection(config, observer);

// Add the track to the peer connection
List&lt;String&gt; streamIds = new ArrayList&lt;&gt;();
streamIds.add("stream1");
peerConnection.addTrack(videoTrack, streamIds);
</pre>

## Additional Features

The `VideoDesktopSource` provides additional methods for controlling the desktop capture:

### Capture Control

<pre>
// Start capturing
videoSource.start();

// Stop capturing
videoSource.stop();

// Check if capturing is active
boolean isCapturing = videoSource.isRunning();
</pre>

### Source Selection

You can change the capture source at runtime:

<pre>
// Switch to a different screen
videoSource.setSourceId(newScreenId, false);

// Switch to a window
videoSource.setSourceId(windowId, true);
</pre>

### Capture Configuration

You can adjust capture settings:

<pre>
// Change frame rate
videoSource.setFrameRate(15);  // Set to 15 fps

// Change maximum resolution
videoSource.setMaxFrameSize(1280, 720);  // Set to 720p
</pre>

### Resource Management

Always properly dispose of resources when done:

<pre>
// Dispose of resources when done
videoSource.stop();
videoSource.dispose();
</pre>

### Handling Source Changes

If desktop sources might change during your application's lifecycle (e.g., new windows opening or screens connecting), you should periodically refresh the source list:

<pre>
// Refresh the list of available sources
ScreenCapturer screenCapturer = new ScreenCapturer();
List&lt;DesktopSource&gt; updatedScreens = screenCapturer.getDesktopSources();

WindowCapturer windowCapturer = new WindowCapturer();
List&lt;DesktopSource&gt; updatedWindows = windowCapturer.getDesktopSources();

// Clean up
screenCapturer.dispose();
windowCapturer.dispose();
</pre>

---

This example demonstrates how to set up a WebRTC peer connection with desktop video capture capabilities. For more information, refer to the [webrtc-java documentation](https://github.com/devopvoid/webrtc-java).