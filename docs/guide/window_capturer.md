# Window Capturer

The `WindowCapturer` enumerates and captures individual application windows. Use it to list open windows and to drive window-source selection for desktop capture workflows.

API: `dev.onvoid.webrtc.media.video.desktop.WindowCapturer`

## Overview

- Purpose: Discover and capture specific windows.
- Pairs with: `VideoDesktopSource` to create a video source capturing the chosen window ID.
- Common ops: list windows, select one, configure capture parameters, start capture via callback, or pass the window ID to `VideoDesktopSource`.
- Resource management: Call `dispose()` when finished to free native resources.

Key methods (inherited from `DesktopCapturer`):
- `List<DesktopSource> getDesktopSources()` – enumerate available windows
- `void selectSource(DesktopSource source)` – choose which window to capture
- `void setFocusSelectedSource(boolean focus)` – attempt to focus/raise the selected window
- `void setMaxFrameRate(int maxFrameRate)` – limit the capture frame rate
- `void start(DesktopCaptureCallback callback)` – start capture, frames via callback
- `void captureFrame()` – request a single capture
- `void dispose()` – release resources

## Typical usage: enumerate windows

```java
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;
import java.util.List;

WindowCapturer windowCapturer = new WindowCapturer();
try {
    List<DesktopSource> windows = windowCapturer.getDesktopSources();
    for (DesktopSource w : windows) {
        System.out.printf("Window: %s (ID: %d)%n", w.title, w.id);
    }
}
finally {
    windowCapturer.dispose();
}
```

## Selecting a window and starting capture with a callback

```java
import dev.onvoid.webrtc.media.video.desktop.*;
import dev.onvoid.webrtc.media.video.VideoFrame;

WindowCapturer capturer = new WindowCapturer();
try {
    DesktopSource window = capturer.getDesktopSources().stream().findFirst()
        .orElseThrow(() -> new IllegalStateException("No windows found"));

    capturer.selectSource(window);
    capturer.setMaxFrameRate(30);
    capturer.setFocusSelectedSource(true); // optional

    DesktopCaptureCallback callback = (result, frame) -> {
        if (result == DesktopCapturer.Result.SUCCESS && frame != null) {
            // process VideoFrame for the selected window
        }
    };

    capturer.start(callback);
    capturer.captureFrame();
}
finally {
    capturer.dispose();
}
```

## Using with VideoDesktopSource to create a VideoTrack

```java
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.desktop.*;

WindowCapturer wc = new WindowCapturer();
DesktopSource window = wc.getDesktopSources().get(0);

VideoDesktopSource vds = new VideoDesktopSource();
vds.setFrameRate(30);
vds.setMaxFrameSize(1280, 720);

// Select the window (isWindow = true)
vds.setSourceId(window.id, true);

vds.start();

// ...

vds.stop();
vds.dispose();
wc.dispose();
```

## Integration tips

- Window availability is dynamic; refresh `getDesktopSources()` if the set of open windows changes.
- Not all OSes allow focusing/raising another app’s window; `setFocusSelectedSource` behavior may vary by platform.
- If the window is minimized or occluded, the captured content may be affected depending on platform.

## Related guides

- [Desktop Capture](guide/desktop_capture.md)
- [Power Management](guide/power_management.md)
