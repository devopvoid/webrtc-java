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
// Set frame rate (e.g., 30 fps), from 1 to VideoDesktopSource.MAX_FRAME_RATE
videoSource.setFrameRate(30);

// Set maximum frame size (e.g., 1920x1080). Frames are scaled down to fit
// both limits and keep their aspect ratio.
videoSource.setMaxFrameSize(1920, 1080);

// Select a specific source to capture
// For a screen (isWindow = false)
videoSource.setSourceId(screenId, false);
// OR for a window (isWindow = true)
videoSource.setSourceId(windowId, true);

// Start capturing
videoSource.start();
```

### Letting the User Choose in the System Picker

Instead of listing sources yourself, you can let the user choose a screen or window in the operating system's own picker. It is available on macOS 14 and newer, where it is ScreenCaptureKit's content sharing picker, and on Linux under Wayland, where the desktop portal's picker is always used:

```java
VideoDesktopSource videoSource = new VideoDesktopSource();
videoSource.setFrameRate(30);

if (VideoDesktopSource.isSystemPickerAvailable()) {
    // The picker chooses the source; no setSourceId() is needed.
    videoSource.setUseSystemPicker(true);
}
else {
    videoSource.setSourceId(screenId, false);
}
```

`start()` shows the picker and returns at once. No frames arrive until the user has made a choice. The user can change it later from the macOS menu bar, and the frames follow. If the user cancels the picker, or stops sharing from the menu bar, the source ends (see [Handling the End of Capture](#handling-the-end-of-capture)). Where `isSystemPickerAvailable()` is false, a source set to use the picker ends right away instead of capturing.

On macOS the picker offers a whole screen or one or more windows. Show it for one source at a time: sources waiting on it at the same time all receive the user's choice. To take frames yourself rather than through a track, `ScreenCapturer` and `WindowCapturer` offer the same picker (see [Screen Capturer](/tools/desktop/screen-capturer#letting-the-user-choose-in-the-system-picker)). The [`DesktopPickerExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/DesktopPickerExample.java) shows the picker and reports the frames it receives.

### Creating a Video Track with the Desktop Source

Once you have configured your desktop video source, you can create a video track:

```java
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.VideoTrack;

// Create a PeerConnectionFactory
PeerConnectionFactory factory = new PeerConnectionFactory();

// Create a video track with the desktop source
VideoTrack videoTrack = factory.createVideoTrack("video0", videoSource);

// Keep the resolution, so that text stays readable, also when the bandwidth runs short.
videoTrack.setContentHint(VideoContentHint.TEXT);
```

The [content hint](/guide/media/constraints#content-hint) can be changed at any time, also while the track is sent.

### Adding the Track to a Peer Connection

Add the video track to your peer connection:

```java
import java.util.ArrayList;
import java.util.List;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpSender;

// Assuming you already have a configured RTCPeerConnection
RTCPeerConnection peerConnection = factory.createPeerConnection(config, observer);

// Add the track to the peer connection
List<String> streamIds = new ArrayList<>();
streamIds.add("stream1");
RTCRtpSender sender = peerConnection.addTrack(videoTrack, streamIds);
```

::: info
`addTrack` returns an `RTCRtpSender`. It is not owned by the peer connection, so dispose it
yourself (`sender.dispose()`) once you no longer need it — typically together with the peer
connection when the call ends.
:::

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

### Handling the End of Capture

`start()` returns before capturing begins. The source ends, and its tracks with it, when capturing cannot begin or cannot go on: no capture backend is available, the selected source does not exist, the captured window was closed, or the user cancelled the system picker or stopped sharing. Create the track and add the listener before calling `start()`, as a listener added later misses an end that comes at once:

```java
videoTrack.addTrackEndedListener(track -> {
    // No more frames will arrive from this source. The listener runs on the
    // capture thread; stopping or disposing the source here is safe.
    videoSource.stop();
});
```

`dispose()` ends the capture as well, even while a track still holds the source.

### Processing Frames

A `VideoDesktopSource` can pass its frames through a `VideoProcessor` before they reach its tracks, for example to blank out a part of the screen. It works as for a camera; see [Changing Frames Before They Are Sent](/guide/video/camera-capture#changing-frames-before-they-are-sent).

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

### Capturing on macOS

On macOS 14 and newer, screens and windows are captured with ScreenCaptureKit; older releases use the earlier Core Graphics capture. Both need the app to have Screen Recording permission (System Settings → Privacy & Security → Screen & System Audio Recording). Without it, the windows of other apps are listed without their titles and their content cannot be captured. Windows of the app's own process need no permission, and are captured with Core Graphics.

While a captured window is minimized or hidden, its last image repeats. When it is closed, the source ends.

## Conclusion

This guide has demonstrated how to set up a WebRTC peer connection with desktop video capture capabilities.
When implementing desktop capture in your application, remember to:

- Always dispose of resources properly to prevent memory leaks
- Periodically refresh source lists to handle dynamic changes in available screens and windows
- Consider the performance implications of higher resolutions and frame rates
- Handle potential permission requirements on different operating systems

Desktop capture is particularly useful for screen sharing applications, remote assistance tools, collaborative workspaces, and educational platforms where visual content sharing is essential.

## Sharing system audio

Add a [`SystemAudioSource`](/guide/audio/system-audio-source) audio track to share
the audio the system plays alongside the desktop video, and the microphone as a
third track if the presenter speaks. All three can come from one factory and go
over one peer connection; put them into one stream so the receiver keeps them in
sync. With the [Client API](/guide/client-api):

```java
LocalVideo screen = rtc.media().screen(ScreenOptions.screen(source).build());
LocalAudio microphone = rtc.media().microphone();

// The source is yours: close it after the runtime, which closes its track.
SystemAudioSource systemOutput = new SystemAudioSource();
LocalAudio systemAudio = rtc.media().audioTrack("system-audio", systemOutput);

peer.addTrack(screen, "presentation");
peer.addTrack(microphone, "presentation");
peer.addTrack(systemAudio, "presentation");

systemOutput.start(); // on a background thread in an application with a UI
```

Window selection applies only to video; the audio source captures the whole
system output. Remote call audio played on the captured output is captured too on
Windows and Linux, so route it to another output. The
[`ScreenShareAudioExample`](/guide/examples#screen-share-with-audio) runs this
end to end.
