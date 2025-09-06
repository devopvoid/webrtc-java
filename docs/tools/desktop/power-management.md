# Power Management

The `PowerManagement` utility allows your application to keep the display awake and prevent the operating system from idling to sleep while the user is considered "active" by your app. This is especially useful during screen sharing, presentations, or long-running desktop capture sessions where user input may be minimal.

API: `dev.onvoid.webrtc.media.video.desktop.PowerManagement`

## Overview

- Purpose: Temporarily prevent the system from sleeping due to user idle.
- Scope: Affects display sleep/idle behavior while enabled.
- Cross‑platform: Implements native integrations for Windows, Linux, and macOS.
- Responsibility: You must explicitly disable the assertion when you are done.

Key methods:
- `void enableUserActivity()` – Declare the user as active; prevents idle sleep.
- `void disableUserActivity()` – Revoke the assertion; system idle behavior resumes normally.

## Typical usage

Call `enableUserActivity()` when you start an operation that must keep the display awake (e.g., desktop capture or a presentation). Always pair it with `disableUserActivity()` (for example, in a `finally` block) to restore the normal power behavior.

```java
import dev.onvoid.webrtc.media.video.desktop.PowerManagement;

PowerManagement pm = new PowerManagement();

// Example: keep display awake during a screen sharing session
pm.enableUserActivity();
try {
    // Start and run your desktop capture / screen sharing pipeline
    // ...
}
finally {
    // Always restore normal behavior
    pm.disableUserActivity();
}
```

## Integration tips

- Lifetime management: Keep the `PowerManagement` instance for as long as you need the assertion. It's safe to call `enableUserActivity()` once at the start and `disableUserActivity()` once at the end.
- Fail‑safe: If your workflow can terminate unexpectedly, ensure `disableUserActivity()` is called (e.g., in `finally` blocks, shutdown hooks, or close handlers).
- Minimal footprint: Only enable while strictly necessary. Do not keep the assertion enabled longer than needed.

## Platform notes

- Windows: Uses native Windows power APIs to request that the display remain on while enabled.
- Linux (Freedesktop environments): Uses DBus screensaver inhibition (e.g., org.freedesktop.ScreenSaver) where available.
- macOS: Uses macOS power management assertions to prevent display sleep while enabled.

Exact mechanisms are handled by the native layer; your Java code remains the same across platforms.

## When to use

- While capturing or sharing the desktop to prevent the monitor from sleeping during inactivity.
- During long‑running, unattended demos, playback, or monitoring dashboards where user input is infrequent.

## Related guides

- [Desktop Capture](/guide/video/desktop-capture)
- [Logging](/guide/monitoring/logging)
