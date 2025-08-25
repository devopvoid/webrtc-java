# Video Capture

The `VideoCapture` class represents a controllable video capture device and coordinates device selection, capability negotiation, frame delivery to a sink, and lifecycle/resource management.

API: `dev.onvoid.webrtc.media.video.VideoCapture`

## Overview

- Purpose: Control a physical or virtual video input device and deliver frames to a sink.
- Typical workflow: set device ➜ set capability ➜ set sink ➜ start ➜ stop ➜ dispose.
- Resource management: Always call `dispose()` to release native resources.
- Threading: Methods are not guaranteed to be thread‑safe; synchronize externally if accessed from multiple threads.

Key methods:
- `void setVideoCaptureDevice(VideoDevice device)` – select the camera/device to bind.
- `void setVideoCaptureCapability(VideoCaptureCapability capability)` – configure desired resolution, frame rate, pixel format, etc.
- `void setVideoSink(VideoTrackSink sink)` – register/replace the sink that will receive frames.
- `void start()` – begin asynchronous capture and frame delivery.
- `void stop()` – stop capture (idempotent).
- `void dispose()` – release native resources; implicitly stops if running.

Related types:
- `VideoDevice` – describes a capturable device (enumerate via your platform/device utilities).
- `VideoCaptureCapability` – resolution, FPS, and pixel format preferences.
- `VideoTrackSink` – consumer that receives `VideoFrame` callbacks.

## Typical usage

```java
import dev.onvoid.webrtc.media.video.*;

// 1) Create capture
VideoCapture capture = new VideoCapture();

try {
    // 2) Select a device (obtained from your device enumeration logic)
    VideoDevice device = /* obtain a VideoDevice */;
    capture.setVideoCaptureDevice(device);

    // 3) Configure capability
    VideoCaptureCapability cap = new VideoCaptureCapability();
    cap.width = 1280;
    cap.height = 720;
    cap.maxFPS = 30;
    // cap.pixelFormat = ... // if applicable in your build
    capture.setVideoCaptureCapability(cap);

    // 4) Provide a sink to receive frames
    VideoTrackSink sink = frame -> {
        // Consume VideoFrame
        // e.g., render, encode, or forward to a WebRTC VideoSource/Track
    };
    capture.setVideoSink(sink);

    // 5) Start capture
    capture.start();

    // ... capture is running, frames delivered to sink ...

    // 6) Stop capture
    capture.stop();
}
finally {
    // 7) Cleanup
    capture.dispose();
}
```

Notes:
- Call `setVideoCaptureDevice` and `setVideoCaptureCapability` before `start()`.
- You may call `setVideoSink` before or after `start()`; passing `null` detaches the sink (if supported), dropping frames until a new sink is set.
- Some capability values may be negotiated to the nearest supported values by the underlying platform.

## Integration with WebRTC tracks

While `VideoCapture` handles raw capture, most WebRTC pipelines use a `VideoSource`/`VideoTrack` abstraction. If you need to feed frames into a `VideoTrack`, use or implement a sink that forwards frames to your `VideoSource` (or use a higher‑level helper provided by this project/examples).

Example sketch:

```java
VideoTrackSink sink = frame -> {
    // Convert/forward frame into your WebRTC VideoSource or renderer
};

capture.setVideoSink(sink);
capture.start();
```

If you need pixel format conversion, see `VideoBufferConverter`.

## Error handling and lifecycle

- Constructor: Initializes native resources; failures may surface as runtime exceptions from native code.
- `IllegalStateException`: Thrown if methods are invoked after disposal or when prerequisites are missing.
- Idempotency: `start()` and `stop()` are intended to be safe to call multiple times (implementation‑dependent no‑op if already in that state).
- Always call `dispose()` in a `finally` block to avoid leaking native resources.

## Tips

- Device changes (plug/unplug cameras) may require re‑enumeration and re‑selection via `setVideoCaptureDevice`.
- If frames need scaling or color conversion, use `VideoBufferConverter` before feeding frames to encoders or renderers.
- Synchronize access if multiple threads change device, capability, or sink while capturing.

## Related guides

- [Camera Capture](guide/camera_capture.md)
- [Custom Video Source](guide/custom_video_source.md)
- [Screen Capturer](guide/screen_capturer.md)
- [Window Capturer](guide/window_capturer.md)
- [Power Management](guide/power_management.md)
