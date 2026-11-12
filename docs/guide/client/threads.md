# Threads

Every method of the client API may be called from any thread, including from inside a handler. Methods that change a peer return at once and take effect in the order they were called.

| Code | Runs on | May it block? |
|---|---|---|
| Your calls into the API | your thread | They don't block, with these exceptions. `WebRtc.createPeer`, the `LocalMedia` methods that make a track and `LocalTrack.setEnabled` and `LocalVideo.setContentHint` wait briefly for WebRTC. `camera()` and `screen()` also open their capture on your thread, which can take longer, so a UI thread should leave them to another thread. The first `media.devices()`, `screens()` and `windows()` ask the system on your thread. `WebRtc.close()` waits up to the close timeout. |
| Peer, track and channel handlers, future callbacks, `Signaling.send` | the peer's event thread | Yes, but that delays only the events of this peer. They must not wait for a future of this API, see below. |
| `onFrame` and `onAudio` handlers | a thread of each subscription | Better not. A slow video handler gets the newest frame next, and the frames in between are dropped; a slow audio handler loses the oldest audio beyond its buffer. |
| [Encoded transforms](/guide/client/transforms) | a thread of each sender and receiver | Better not. A slow transform delays only its own frames, and one that falls several seconds behind has frames dropped. |
| `LocalTrack.onEnded` and `Devices.onChange` handlers, callbacks of the futures of local tracks and devices | the event thread of the runtime's local media | Yes, but that delays the other events of local media. They must not wait for a future of this API either. A track is released before its `onEnded` handlers run, so they never hold up a release. |
| WebRTC's own callbacks | WebRTC's threads | Not your code. The library only copies what they report and queues it. |

The handlers of one peer never run at the same time, and they run in the order of the events. The handlers of different peers may run at the same time. With the default executor, the event thread is named after its peer, such as `webrtc-client-events-alice`, which shows up in thread dumps.

An executor of your own that rejects work is described under [Details](#executors-that-reject-work).

## Limiting Threads

By default the runtime starts a thread for each peer whose events wait and for each subscription whose frames wait, so a thousand peers with slow handlers take a thousand threads. `eventThreads(n)` and `frameThreads(n)` cap them:

```java
WebRtc rtc = WebRtc.builder()
        .eventThreads(8)     // handlers of all peers, future callbacks, Signaling.send
        .frameThreads(4)     // onFrame and onAudio handlers, local previews included
        .build();
```

With a limit, the peers take turns, and so do the subscriptions: one that has work runs up to 64 handlers, for at most 10 ms, then goes to the back of the line. A slow handler thus delays the others but never starves them, and a peer's events still run one at a time and in order. The limit rejects nothing: events wait for their turn and are never dropped, so no future stays open, and once the runtime closes, the futures still open fail after the events queued before them. Media that waits is bounded by its subscription instead, by the `FrameDelivery` of a video handler and the audio buffer of an audio handler, which drop the oldest frames and audio when they are full.

A limit has one cost: a handler that waits for what another peer's handler does, say on a latch, can wait forever once all threads are taken by such waits. Handlers that wait for something outside the API should hand the work to a thread of their own.

The control threads of the library, which call into WebRTC, are not limited. Their tasks wait for one another, such as a camera switch for every peer to take the new track.

The HTTP requests of `Whip` and `Whep` sessions take no thread while they wait, so a slow server holds up no peer: they go through Java's `HttpClient`, one of each runtime.

`rtc.loadStats()` tells how much work waits and runs, at this moment and cheaply enough to be read every second:

```java
LoadStats load = rtc.loadStats();

LoadStats.Work events = load.events();
metrics.gauge("webrtc.events.queued", events.queued());                     // events waiting for a thread
metrics.gauge("webrtc.events.oldest_ms", events.oldestWaiting().toMillis()); // how long the oldest has waited
metrics.gauge("webrtc.events.active", events.active());                     // handlers running now
metrics.gauge("webrtc.video.dropped", load.video().dropped());              // frames dropped since start
metrics.gauge("webrtc.audio.dropped", load.audio().dropped());              // audio chunks dropped since start
metrics.gauge("webrtc.event_threads", load.eventThreads());
```

| Part | What waits | What `dropped()` counts |
|---|---|---|
| `control()` | tasks of the library that call into WebRTC | tasks the control pool rejected while running, which it never does |
| `events()` | events of all peers, for handlers, future callbacks and `Signaling.send` | events an `eventExecutor` of your own rejected |
| `video()` | frames for `onFrame` handlers | frames a handler had no time for |
| `audio()` | chunks for `onAudio` handlers | chunks beyond the audio buffer |

A queue that keeps growing, or an oldest wait that keeps getting longer, tells that handlers do not keep up. A higher limit helps handlers that wait for I/O; handlers that compute only spread the delay over more threads.

## Handlers on the UI Thread

Pass the UI toolkit's executor, and every handler may touch UI components directly:

```java
WebRtc rtc = WebRtc.builder()
        .eventExecutor(SwingUtilities::invokeLater)   // or Platform::runLater for JavaFX
        .build();

peer.onStateChange(state -> statusLabel.setText(state.toString()));
hangUpButton.addActionListener(event -> peer.close());   // never blocks the EDT
```

The order of each peer's events is kept with any executor, including a thread pool. Frame handlers stay on their own threads even then, since frames come far too often for a UI thread.

To show video in Swing or JavaFX, see [Showing Video](/guide/client/receiving-media#showing-video).

## Don't Wait in a Handler

A future of the client API completes on the event thread of its peer. A handler that waits for one would wait for its own thread, so `join()` and `get()` there throw a `BlockingCallException` instead of hanging. Chain the next step instead:

```java
peer.onChannel(channel -> {
    // channel.whenOpen().join() would throw here.
    channel.whenOpen().thenCompose(open -> open.send("hello"));
});
```

A future that is already done may be read anywhere, and any thread that is not an event thread, such as `main`, may wait.

Each call returns a future of its own, also of `closeAsync()`, `whenClosed()`, `whenConnected()` and `whenOpen()`. Cancelling one only stops waiting: the close goes on, and the futures of other callers, and of the runtime's own close, are not affected.

Futures derived from them with `thenApply` and the like refuse to be waited on as well.

::: warning
A UI thread used as event executor does not refuse: waiting on it for a future of this API hangs the UI, since the future completes on it.
:::

## Details

### Executors That Reject Work

An executor of your own that rejects work, such as a bounded pool that is full, loses the events it rejects, and the rejection goes to the error handler. The futures those events would have completed complete all the same, on the thread that found them rejected. Once the runtime is closed, every future of this API that is not done fails with `PeerClosedException`, including those of calls made after the close.
