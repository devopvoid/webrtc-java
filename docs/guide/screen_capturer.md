# Screen Capturer

The `ScreenCapturer` enumerates and captures full desktop screens/monitors. Use it to list available screens and to drive screen-source selection for desktop capture workflows.

API: `dev.onvoid.webrtc.media.video.desktop.ScreenCapturer`

## Overview

- Purpose: Discover and capture full screens (monitors).
- Pairs with: `VideoDesktopSource` to produce a capturable video track from a selected screen ID.
- Common ops: list screens, select one, configure capture parameters on `DesktopCapturer`, start capture via callback, or pass the ID to `VideoDesktopSource`.
- Resource management: Call `dispose()` when finished to free native resources.

Key methods (inherited from `DesktopCapturer`):
- `List<DesktopSource> getDesktopSources()` – enumerate available screens
- `void selectSource(DesktopSource source)` – choose the screen to capture
- `void setFocusSelectedSource(boolean focus)` – try to focus the selected source during capture
- `void setMaxFrameRate(int maxFrameRate)` – cap the capture FPS
- `void start(DesktopCaptureCallback callback)` – begin capturing; frames are delivered via callback
- `void captureFrame()` – request a single frame (manual capture)
- `void dispose()` – release resources

## Typical usage: enumerate screens

```java
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import java.util.List;

ScreenCapturer screenCapturer = new ScreenCapturer();
try {
    List<DesktopSource> screens = screenCapturer.getDesktopSources();
    for (DesktopSource s : screens) {
        System.out.printf("Screen: %s (ID: %d)%n", s.title, s.id);
    }
}
finally {
    screenCapturer.dispose();
}
```

## Selecting a screen and starting capture with a callback

```java
import dev.onvoid.webrtc.media.video.desktop.*;
import dev.onvoid.webrtc.media.video.VideoFrame;

ScreenCapturer capturer = new ScreenCapturer();
try {
    // pick the first screen
    DesktopSource screen = capturer.getDesktopSources().stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("No screens found"));

    capturer.selectSource(screen);
    capturer.setMaxFrameRate(30);
    capturer.setFocusSelectedSource(false);

    DesktopCaptureCallback callback = (result, frame) -> {
        if (result == DesktopCapturer.Result.SUCCESS && frame != null) {
            // process VideoFrame
        }
    };

    capturer.start(callback);

    // Optionally trigger ad-hoc capture
    capturer.captureFrame();
}
finally {
    capturer.dispose();
}
```

## Using with VideoDesktopSource to create a VideoTrack

For most WebRTC pipelines, you will create a `VideoDesktopSource` and set the selected screen ID:

```java
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.desktop.*;

ScreenCapturer sc = new ScreenCapturer();
DesktopSource screen = sc.getDesktopSources().get(0);

VideoDesktopSource vds = new VideoDesktopSource();
vds.setFrameRate(30);
vds.setMaxFrameSize(1920, 1080);

// Select the screen (isWindow = false)
vds.setSourceId(screen.id, false);

// Start the source and use it to create a VideoTrack in your PeerConnection
vds.start();

// ...

// cleanup
vds.stop();
vds.dispose();
sc.dispose();
```

## Integration tips

- If screens can change (hot‑plug monitors), refresh `getDesktopSources()` as needed.
- Use `setMaxFrameRate` to limit capture load on the system.
- Prefer using `VideoDesktopSource` for WebRTC pipelines; keep `ScreenCapturer` for discovery and advanced control.

## Related guides

- [Desktop Capture](guide/desktop_capture.md)
- [Power Management](guide/power_management.md)
