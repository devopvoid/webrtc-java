# Client API

The client API in `dev.onvoid.webrtc.client` is a layer over the rest of the library that takes care of the parts most applications get wrong: threads, the offer and answer exchange, and the lifetime of native objects. A whole video call fits on one screen:

```java
import dev.onvoid.webrtc.client.*;

try (WebRtc rtc = WebRtc.create()) {
    Peer peer = rtc.createPeer(PeerOptions.builder()
            .iceServer("stun:stun.l.google.com:19302")
            .polite(joinedSecond)
            .signaling(message -> socket.send(message.toJson()))
            .build());
    socket.onText(text -> peer.signal(SignalMessage.fromJson(text)));

    peer.addTrack(rtc.media().microphone());
    peer.addTrack(rtc.media().camera());

    peer.onTrack(track -> {
        if (track.isVideo()) {
            track.asVideo().onFrame(frame -> view.render(frame));
        }
    });
    peer.onStateChange(state -> System.out.println("Call is " + state));

    peer.whenConnected().join();
    hangUp.await();
}
```

Closing the `WebRtc` runtime at the end closes the peer, the camera, the microphone and the factory, in that order. There is no observer to implement and no `dispose()` to call.

What the client API takes care of:
- **Threads**: your handlers never run on a thread WebRTC needs, so they may block or close a peer without stalling or deadlocking anything. The handlers of one peer run one at a time, in order.
- **Negotiation**: peers negotiate by themselves whenever tracks or channels change, with [perfect negotiation](https://w3c.github.io/webrtc-pc/#perfect-negotiation-example). Offer collisions are resolved, and candidates that arrive before their description are held.
- **Lifetime**: everything belongs to the runtime that created it and is closed with it. Senders, receivers and transceivers stay inside the library.
- **Futures**: operations return `CompletableFuture`s that fail with typed exceptions, instead of taking observers.

The client API is plain Java on top of the classes the other guides describe, and it does not replace them. Its peers, tracks and channels are the same native objects, and [Using the Low-Level API](#using-the-low-level-api) shows how to reach them for anything it does not cover.

## The Runtime

A `WebRtc` runtime owns a `PeerConnectionFactory`, its audio device module and the threads of the client API. `WebRtc.create()` captures the microphone and plays to the speakers. The builder sets everything else:

```java
WebRtc rtc = WebRtc.builder()
        .audio(AudioMode.HEADLESS)
        .hardwareVideoCodecs(true)
        .eventExecutor(SwingUtilities::invokeLater)
        .errorHandler(error -> log.error("WebRTC", error))
        .operationTimeout(Duration.ofSeconds(30))
        .closeTimeout(Duration.ofSeconds(10))
        .build();
```

| Method | Default | What it sets |
|---|---|---|
| `audio(AudioMode)` | `DEVICES` | Where sent audio comes from, see below. |
| `audioProcessing(AudioProcessingConfig)` | none | The [audio processing](/guide/audio/audio-processing) of captured audio. |
| `hardwareVideoCodecs(boolean)` | `false` | Encodes and decodes video on the GPU where it can, as the [Video Codecs](/guide/advanced/video-codecs) guide describes. |
| `fieldTrials(Map)` | none | [Field trials](/guide/advanced/field-trials). |
| `eventExecutor(Executor)` | threads of the runtime | Where handlers run, see [Threads](#threads). |
| `eventThreads(int)` | unlimited | The most threads the runtime runs handlers on, see [Limiting Threads](#limiting-threads). |
| `frameThreads(int)` | unlimited | The most threads that run `onFrame` and `onAudio` handlers. |
| `audioBuffer(Duration)` | 5 seconds | How much audio an `onAudio` subscription keeps for a handler that falls behind, see [Audio](#audio). |
| `errorHandler(Consumer<Throwable>)` | the thread's uncaught exception handler | Receives what handlers throw, and failures nobody else hears of. |
| `operationTimeout(Duration)` | 30 seconds | How long an operation such as applying a description may take before it fails with a `TimeoutException`. |
| `closeTimeout(Duration)` | 10 seconds | How long `close()` waits. |
| `configureFactory(Consumer<PeerConnectionFactory.Builder>)` | none | Anything else of the factory, such as codec factories of your own. |

Every timeout of the client API, these two as well as the gathering timeout and the `Whip` and `Whep` timeouts below, must be positive; none of them can be switched off. A timeout counts in milliseconds, rounded up, so one under a millisecond waits a millisecond. One too long for milliseconds to count waits as long as they can count, about 292 million years.

### Audio Modes

WebRTC feeds the audio a factory captures into every audio sender of that factory, so captured audio and audio you push cannot be mixed in one runtime. The runtime's `AudioMode` picks one of them up front:

| Mode | Sends | Plays remote audio | Use it for |
|---|---|---|---|
| `DEVICES` | the microphone | to the speakers | calls on a desktop |
| `CUSTOM` | audio pushed through `CustomAudio` | to the speakers | applications that generate or relay audio |
| `HEADLESS` | audio pushed through `CustomAudio` | to `RemoteAudio.onAudio` handlers only | servers, bots and tests, without any audio hardware |

Asking a runtime for the other kind fails at once with a `ConfigurationException` that names the mode, rather than when the first track is added. Two runtimes can run side by side if an application needs both kinds.

## Peers and Signaling

`rtc.createPeer(options)` creates a connection to one remote peer. It is the one call of the client API that waits, briefly, for the connection to be created, so that a configuration WebRTC refuses fails right there with a `ConfigurationException`.

```java
Peer peer = rtc.createPeer(PeerOptions.builder()
        .name("alice")
        .iceServer("stun:stun.l.google.com:19302")
        .iceServer("turn:turn.example.com:3478", "user", "secret")
        .polite(true)
        .signaling(message -> socket.send(message.toJson()))
        .build());
```

| Method | What it sets |
|---|---|
| `name(String)` | The name in thread names and error messages. |
| `iceServer(String...)` | A STUN or TURN server without credentials. |
| `iceServer(String, String, String)` | A TURN server with a user name and password. |
| `relayOnly(boolean)` | Connects through TURN servers only, which hides this side's addresses. |
| `signaling(Signaling)` | What carries messages to the remote peer. Required unless negotiation is manual. |
| `polite(boolean)` | The role in perfect negotiation, see below. |
| `negotiation(Negotiation)` | `AUTOMATIC` by default, or `MANUAL`, see [Manual Negotiation](#manual-negotiation). |
| `receive(MediaKind...)` | Receives audio or video without sending any, as a viewer does. |
| `gatheringTimeout(Duration)` | How long `Gathering.COMPLETE` waits for ICE gathering, 3 seconds by default. See [Manual Negotiation](#manual-negotiation). |
| `channelSendLimit(long)` | How many bytes each data channel may hold waiting to be sent, 16 MiB by default. See [Data Channels](#data-channels). |
| `channelReceiveLimit(long)` | How many bytes of received messages each data channel may hold that its handlers are not done with, 16 MiB by default. See [Data Channels](#data-channels). |
| `configure(Consumer<RTCConfiguration>)` | Anything else the [peer connection configuration](/guide/networking/peer-connection-config) has. |

### Signaling

The library has no transport of its own. A `Signaling` sends a peer's messages to the remote peer, and messages from the remote peer are handed to `peer.signal(...)`, in the order they were sent:

```java
// Outgoing: called on the peer's event thread, in order.
Signaling signaling = message -> socket.send(message.toJson());

// Incoming: from any thread.
socket.onText(text -> peer.signal(SignalMessage.fromJson(text)));
```

A transport class can implement both directions by overriding `Signaling.attach(Consumer<SignalMessage>)`, which the peer calls once when it is created, with the consumer to push received messages into.

A `SignalMessage` is a session description (`OFFER`, `ANSWER`) or an ICE candidate (`CANDIDATE`). `toJson()` writes the shapes the browser API uses, so a JavaScript peer can pass them to `setRemoteDescription` and `addIceCandidate` as they are:

```json
{"type":"offer","sdp":"v=0\r\no=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n..."}
{"candidate":"candidate:842163049 1 udp 1677729535 203.0.113.7 49203 typ srflx raddr 0.0.0.0 rport 0","sdpMid":"0","sdpMLineIndex":0}
```

`SignalMessage.fromJson(String)` reads these, what a browser's `JSON.stringify(pc.localDescription)` and `JSON.stringify(candidate)` write, and the `{"description": ...}` and `{"candidate": {...}}` envelopes of the perfect negotiation examples. Anything else throws an `IllegalArgumentException` that says what is wrong and where, as does text longer than a MiB or nested deeper than 64 levels, so a message from the remote side cannot exhaust the stack. The JSON must be strict (RFC 8259): no raw line breaks or other control characters inside strings, no member name twice in one object, no numbers like `01`, `1.` or `1e999`. An `sdpMLineIndex` must be an integer from 0 to 65535; `1.9` is rejected rather than truncated to 1. Routing, such as room names or peer ids, belongs in an envelope of your own around this JSON.

A browser peer that only answers needs no more than this:

```js
socket.onmessage = async ({ data }) => {
    const message = JSON.parse(data);

    if (message.type) {
        await pc.setRemoteDescription(message);

        if (message.type === "offer") {
            await pc.setLocalDescription();
            socket.send(JSON.stringify(pc.localDescription));
        }
    }
    else if (message.candidate) {
        await pc.addIceCandidate(message);
    }
};

pc.onicecandidate = ({ candidate }) => candidate && socket.send(JSON.stringify(candidate));
```

A browser peer that adds tracks of its own sends offers too, and then has to implement perfect negotiation as well, as the W3C example linked above does.

For tests and demos, `Signaling.loopback()` links two peers in the same process:

```java
SignalingPair link = Signaling.loopback();

Peer caller = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
Peer callee = rtc.createPeer(PeerOptions.builder().signaling(link.second()).polite(true).build());
```

### Polite and Impolite Peers

With automatic negotiation, a peer sends an offer whenever its tracks or channels change. When both peers send one at the same moment, the **polite** peer drops its own offer and answers the other one, and the impolite peer ignores the offer it got. Exactly one of two peers must be polite. Peers are impolite by default, so set `polite(true)` on one side, for example on the peer that joined second.

When two impolite peers collide, neither gives way. An impolite peer that waited 5 seconds for an answer reports a `NegotiationException` that says to make one of them polite.

### Peer State

`peer.state()` returns the last state the connection reported:

| State | Meaning |
|---|---|
| `NEW` | Created, nothing negotiated yet. |
| `CONNECTING` | ICE and DTLS are establishing the connection. |
| `CONNECTED` | Media and data can flow. |
| `DISCONNECTED` | Connectivity was lost. It may come back by itself. |
| `FAILED` | The connection failed. `peer.restartIce()` tries to recover it. |
| `CLOSED` | The peer was closed. This state is final. |

```java
peer.onStateChange(state -> statusLabel.setText(state.toString()));

peer.whenConnected().thenAccept(p -> System.out.println("Connected"));
peer.whenClosed().thenRun(() -> System.out.println("Closed"));
```

`onStateChange` calls a new handler with the current state first, so a handler added late still learns where the peer stands. `whenConnected()` fails if the connection fails or closes first.

## Threads

Every method of the client API may be called from any thread, including from inside a handler. None of them waits for a WebRTC thread: methods that change a peer return at once and take effect in the order they were called.

| Code | Runs on | May it block? |
|---|---|---|
| Your calls into the API | your thread | They don't block. The exceptions are `WebRtc.createPeer`, which waits briefly for the connection, and `WebRtc.close()`, which waits up to the close timeout. |
| Peer, track and channel handlers, future callbacks, `Signaling.send` | the peer's event thread | Yes, but that delays only the events of this peer. They must not wait for a future of this API, see below. |
| `onFrame` and `onAudio` handlers | a thread of each subscription | Better not. A slow video handler gets the newest frame next, and the frames in between are dropped; a slow audio handler loses the oldest audio beyond its buffer. |
| [Encoded transforms](#encoded-transforms) | a thread of each sender and receiver | Better not. A slow transform delays only its own frames, and one that falls several seconds behind has frames dropped. |
| `LocalTrack.onEnded` and `Devices.onChange` handlers, callbacks of the futures of local tracks and devices | the event thread of the runtime's local media | Yes, but that delays the other events of local media. They must not wait for a future of this API either. A track is released before its `onEnded` handlers run, so they never hold up a release. |
| WebRTC's own callbacks | WebRTC's threads | Not your code. The library only copies what they report and queues it. |

The handlers of one peer never run at the same time, and they run in the order of the events. The handlers of different peers may run at the same time. With the default executor, the event thread is named after its peer, such as `webrtc-client-events-alice`, which shows up in thread dumps.

An executor of your own that rejects work, such as a bounded pool that is full, loses the events it rejects, and the rejection goes to the error handler. The futures those events would have completed complete all the same, on the thread that found them rejected. Once the runtime is closed, every future of this API that is not done fails with `PeerClosedException`, including those of calls made after the close.

### Limiting Threads

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

### Handlers on the UI Thread

Pass the UI toolkit's executor, and every handler may touch UI components directly:

```java
WebRtc rtc = WebRtc.builder()
        .eventExecutor(SwingUtilities::invokeLater)   // or Platform::runLater for JavaFX
        .build();

peer.onStateChange(state -> statusLabel.setText(state.toString()));
hangUpButton.addActionListener(event -> peer.close());   // never blocks the EDT
```

The order of each peer's events is kept with any executor, including a thread pool. Frame handlers stay on their own threads even then, since frames come far too often for a UI thread.

`VideoView` in `dev.onvoid.webrtc.client.swing` shows video in a Swing window. It converts each frame on the frame's own thread and leaves only the painting to the EDT. It keeps the picture's aspect ratio and rotation, and can mirror a local camera preview:

```java
import dev.onvoid.webrtc.client.swing.VideoView;

VideoView remoteView = new VideoView();
VideoView preview = new VideoView();
preview.setMirrored(true);

peer.onTrack(track -> {
    if (track.isVideo()) {
        track.asVideo().onFrame(remoteView);
    }
});
camera.onFrame(preview);

frame.add(remoteView, BorderLayout.CENTER);
```

`snapshot()` returns a copy of the picture shown, for saving a still or for tests.

For JavaFX, the `webrtc-java-javafx` module has a `VideoView` node in `dev.onvoid.webrtc.client.javafx` that works the same way. It needs Java 11 and the JavaFX your application already uses:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java-javafx</artifactId>
    <version>{{ VERSION }}</version>
</dependency>
```

```java
import dev.onvoid.webrtc.client.javafx.VideoView;

WebRtc rtc = WebRtc.builder()
        .eventExecutor(Platform::runLater)    // handlers on the JavaFX Application Thread
        .build();

VideoView remoteView = new VideoView();
peer.onTrack(track -> {
    if (track.isVideo()) {
        track.asVideo().onFrame(remoteView);
    }
});

stage.setScene(new Scene(new StackPane(remoteView), 960, 540));
```

The view converts frames on their own thread and paints at most once per JavaFX pulse. `setMirrored(true)`, or its `mirroredProperty()`, flips a camera preview, and `snapshot()`, on the JavaFX Application Thread, returns a copy of the picture. The `JavaFxCameraExample` in the [examples](/guide/examples#javafx-camera) shows a preview and a received track side by side.

### Don't Wait in a Handler

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

## Sending Media

`rtc.media()` creates the tracks a runtime sends, and `peer.addTrack(track)` sends one. With automatic negotiation, the peer renegotiates by itself.

```java
LocalMedia media = rtc.media();

LocalCamera camera = media.camera(CameraOptions.builder()
        .device(media.devices().cameras().get(0))
        .resolution(1280, 720)
        .frameRate(30)
        .build());
LocalAudio microphone = media.microphone();

Sender videoSender = peer.addTrack(camera);
Sender audioSender = peer.addTrack(microphone);
```

| Method | Track |
|---|---|
| `camera()`, `camera(CameraOptions)` | A camera, as a `LocalCamera` that can switch cameras. The supported capability nearest to the requested resolution and frame rate is used. |
| `microphone()`, `microphone(AudioOptions)` | The microphone, with echo cancellation, noise suppression and gain control unless `AudioOptions` say otherwise. `DEVICES` mode only. |
| `screen(ScreenOptions)` | A screen or a window, as the [Desktop Video](/guide/video/desktop-capture) guide describes. |
| `customVideo(String)` | Frames you push. |
| `customAudio(String)` | Audio you push. `CUSTOM` and `HEADLESS` modes only. |
| `videoTrack(String, VideoTrackSource)`, `audioTrack(String, AudioTrackSource)` | A source of the core API, such as one the [media module](/guide/media/media-files) feeds. The source stays yours. |

```java
Devices devices = media.devices();
LocalVideo screen = media.screen(ScreenOptions.screen(devices.screens().get(0)).frameRate(15).build());
```

Tracks added without stream ids share one stream per peer, so that a browser plays their audio and video in sync. Pass ids to `addTrack(track, streamIds...)` to group them yourself.

One track may be sent on any number of peers. Opening a camera that is already open shares its capture when the options are the same, and fails with a `MediaException` when they differ.

### Sending a Media File

The media module plays files and RTSP streams into sources of the core API. `videoTrack` and `audioTrack` put them on peers like any other track:

```java
WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build();   // first: it loads the native library
MediaFileSource file = new MediaFileSource(Paths.get("movie.mp4"));

peer.addTrack(rtc.media().videoTrack("movie", file.getVideoSource()));
peer.addTrack(rtc.media().audioTrack("movie", file.getAudioSource()));
file.play();
```

Create the runtime before the file: the runtime loads the native library that the file's sources need. The sources belong to the `MediaFileSource`, not to the tracks, so close the runtime, or the tracks, before the file: `track.closeAsync()` returns a future that completes once the track let go of its source. The file's audio source is pushed audio, so the runtime needs the `CUSTOM` or `HEADLESS` audio mode.

### Pushing Video and Audio

```java
CustomVideo video = media.customVideo("render");

VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
video.push(frame);
frame.release();
```

`push(frame)` treats the frame as captured now. A source with timing of its own, such as a file, passes a timestamp with `push(frame, timestampMicros)`; see [Custom Video Source](/guide/video/custom-video-source) for why that matters.

```java
CustomAudio voice = media.customAudio("voice");

// 16-bit little-endian PCM, any length, from any thread.
voice.push(pcm, PcmFormat.of(48000, 1));
```

`CustomAudio` takes PCM in pieces of any length and from any thread. It cuts the audio into the 10 ms chunks WebRTC works with and sends one every 10 ms from a thread of its own, so pushing a whole second at once is as fine as pushing every 10 ms. `queuedMillis()` tells how much is still waiting, and `clear()` drops it. That thread does nothing else, so a slow push into WebRTC delays only the audio, not the timeouts of the runtime. Should it stall, as in a long garbage collection pause, it makes up for at most the last 50 ms instead of sending the whole pause at once; the queued audio then stays behind by the rest, until `clear()` drops it.

A producer faster than real time would otherwise fill memory and push the delay up without end, so the queue holds at most 10 seconds, and a `push` beyond that fails with an `AudioQueueFullException`, keeping none of its audio. `queueLimit(max, overflow)` sets another limit and what happens beyond it:

```java
// Live audio: stay at most 200 ms behind, dropping the oldest audio.
voice.queueLimit(Duration.ofMillis(200), AudioOverflow.DROP_OLDEST);

// A file, which must arrive whole: refuse the push, to try it again later.
voice.queueLimit(Duration.ofSeconds(2), AudioOverflow.REJECT);
```

With `REJECT` a refused push can be pushed again once `queuedMillis()` has shrunk; with `DROP_OLDEST` every push is taken, and the audio sent lags at most the limit behind.

### Controlling Senders and Tracks

```java
camera.setEnabled(false);                      // sends black frames, the camera stays open

videoSender.configure(Encoding.builder()
        .maxBitrate(1_500_000)
        .maxFrameRate(30)
        .build());

videoSender.replaceTrack(screen);              // no renegotiation
audioSender.remove();                          // renegotiates; the track stays open

camera.close();                                // leaves every peer and stops the camera
```

Closing a track stops sending it on every peer, without renegotiating, and then releases the capture. The senders stay, and can be given another track with `replaceTrack`.

An audio sender also sends DTMF tones, for telephone systems and voice menus. The future completes once the tones have been sent, and `onTone` reports each tone as it starts, then an empty string at the end:

```java
audioSender.onTone(tone -> System.out.println(tone.isEmpty() ? "Done" : "Tone " + tone));
audioSender.sendTones("1234#");                                       // 100 ms each
audioSender.sendTones("*99,1", Duration.ofMillis(200), Duration.ofMillis(100));
```

The characters 0 to 9, A to D, `#` and `*` are tones, and a comma is a pause of 2 seconds. Tones still queued from an earlier call are replaced, and that call's future is cancelled. A video sender, or one whose remote peer accepts no telephone events, fails the future with an `IllegalStateException`. The [DTMF Sender](/guide/audio/dtmf-sender) guide has the details.

### Switching Cameras

A `LocalCamera` switches to another camera without renegotiating. Every peer that sends it keeps sending it, and preview handlers keep getting frames:

```java
LocalCamera camera = media.camera();
peer.addTrack(camera);

camera.switchCamera(media.devices().cameras().get(1))
        .thenRun(() -> System.out.println("Now sending " + camera.device().getName()));
```

The new camera keeps the resolution and frame rate of the old one, as far as it supports them. The switch opens the new camera before it closes the old one, so that the peers see no gap. Some devices cannot capture with two cameras at once, such as the two sensors of a laptop camera; then the old camera closes first, and the switch takes about half a second. If the new camera cannot be opened, the future fails with a `MediaException`, and the track keeps sending the camera it had. If a peer does not take the new camera within the operation timeout, the future fails with a `WebRtcException`; the track goes back to the old camera, or keeps the new one when the old one had to close first. A track that closed its camera first and can open neither camera ends.

### Devices

`media.devices()` returns the cameras, microphones and speakers. The first call reads them from the system; after that, the lists are snapshots kept current in the background, so reading them is cheap on any thread. `screens()` and `windows()` ask the system on each call.

```java
Devices devices = media.devices();

devices.onChange(changed -> System.out.println("Cameras: " + changed.cameras()));
devices.useMicrophone(devices.microphones().get(1));
devices.useSpeaker(devices.speakers().get(0));
```

`useMicrophone` and `useSpeaker` switch devices also for tracks that are sent or played now. A device that was disconnected since the list was read fails the returned future, and the device in use stays as it was. On Linux, where WebRTC's audio device module cannot tell devices apart, the default device is used whichever is given.

## Receiving Media

`peer.onTrack` is called for every track the remote peer sends. Tracks that already arrived are handed to a handler added later.

```java
peer.onTrack(track -> {
    System.out.println("Receiving " + track.kind() + " in " + track.streamIds());

    if (track.isVideo()) {
        track.asVideo().onFrame(frame -> view.render(frame));
    }
    else {
        track.asAudio().onAudio(chunk -> recognizer.feed(chunk.samples()));
    }

    track.onMute(muted -> System.out.println("Muted: " + muted));
    track.onEnded(() -> System.out.println("Track ended"));
});
```

A track ends when the remote peer stops sending it or the peer closes.

### Video Frames

Each `onFrame` subscription has a thread of its own, so a slow renderer holds up neither WebRTC's decoder nor the peer's other events. With `frameThreads(n)` the subscriptions share `n` threads and take turns, see [Limiting Threads](#limiting-threads). By default the handler always gets the newest frame, and the frames it had no time for are dropped. `FrameDelivery.queue(n)` keeps up to `n` frames instead:

```java
video.onFrame(FrameDelivery.queue(10), frame -> encoder.encode(frame));
```

A frame is valid while the handler runs. Call `frame.retain()` to keep it longer, and `frame.release()` when done.

### Audio

`onAudio` hands decoded audio to a handler in order, usually 10 ms of 16-bit PCM per `AudioChunk`. Audio reaches these handlers only while the runtime plays out, which a `HEADLESS` runtime always does and a `DEVICES` or `CUSTOM` runtime does to its speakers. `setVolume(double)` changes the playout gain of a track, from 0 to 10, where 1 plays it as received.

A handler that falls behind gets the audio of the last 5 seconds; the oldest audio beyond that is dropped and counted in `rtc.loadStats().audio().dropped()`. `WebRtc.Builder.audioBuffer(Duration)` changes the buffer of every subscription, and `onAudio(Duration, handler)` that of one. A speech recognizer that must stay live keeps a short buffer; a recorder that can catch up after a stall keeps a long one, at about 190 KiB per second of 48 kHz stereo:

```java
track.asAudio().onAudio(Duration.ofMillis(200), chunk -> recognizer.feed(chunk.samples()));
```

### Receiving Without Sending

A viewer that sends nothing asks for what it wants to receive, so that the offer includes it:

```java
Peer viewer = rtc.createPeer(PeerOptions.builder()
        .receive(MediaKind.VIDEO, MediaKind.AUDIO)
        .signaling(signaling)
        .build());
```

### Recording

The media module's `MediaRecorder` records senders and remote tracks into a file as they are, without re-encoding:

```java
try (MediaRecorder recorder = new MediaRecorder(Paths.get("call.mkv"))) {
    recorder.addTrack(videoSender);
    recorder.addTrack(remoteVideo);
    recorder.start();
    // ...
    recorder.stop();
}
```

Closing the peer, or removing a sender, during a recording ends that track in the file, and `stop()` still finishes it. The [Media Recording](/guide/media/media-recording) guide covers containers and the recorder's events.

### Encoded Transforms

An [encoded transform](/guide/advanced/encoded-transforms) sees every encoded frame between the encoder and the network, or between the network and the decoder, which is where end-to-end encryption goes. The transforms of the peer options apply to every sender and every remote track of the peer:

```java
Peer peer = rtc.createPeer(PeerOptions.builder()
        .signaling(signaling)
        .sendTransform(frame -> encrypt(frame))
        .receiveTransform(frame -> decrypt(frame))
        .build());
```

The peer sets the send transform on each sender as its track is added, before negotiation, so no frame leaves without it and the encoder needs no restart. It sets the receive transform on each receiver before `onTrack` hands out the track, so the transform is in place before the first frame arrives. The builders of [WHIP and WHEP](#whip-and-whep) take them too, `Whip`'s as `sendTransform(...)`, `Whep`'s as `receiveTransform(...)`.

A single sender or remote track takes a transform of its own, in place of the peer's, and `null` removes it:

```java
videoSender.transform(frame -> addMetadata(frame));
remoteTrack.transform(null);                   // frames reach the decoder unchanged
```

The first transform set on a video sender that already sends restarts its encoder, which costs a key frame. A transform set on a remote track misses the frames that arrive before its future completes, so a transform that every frame needs, such as decryption, belongs in the peer options. A recording of a sender holds its frames before the transform, a recording of a remote track after it.

Transforms run on a thread of each sender and receiver, not on the peer's event thread. A frame a transform throws on is dropped rather than passed on, so a failed encryption never sends a frame in the clear. The first such failure of each sender or receiver goes to the peer's `onError` handlers. The [`EncryptedRecordingExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/EncryptedRecordingExample.java) encrypts a call with AES-GCM this way.

## Data Channels

```java
DataChannel chat = peer.createChannel("chat");

chat.onMessage(message -> System.out.println("Peer says: " + message.text()));
chat.send("hello");                          // sent as soon as the channel opens

peer.onChannel(channel -> {                  // channels the remote peer opens
    if (channel.label().equals("files")) {
        channel.onMessage(message -> output.write(message.bytes()));
    }
});
```

- A channel may be used right away: messages sent before it opens are held until it does.
- Messages that arrive while a channel has no `onMessage` handler are kept for the next one, so none are lost while a handler is being set up.
- `send` takes a `String`, a `byte[]` or a `ByteBuffer`, and copies the data before it returns.
- A `Message` holds a copy of the data. `isText()` tells text from binary, `text()` decodes it as UTF-8, and `bytes()` returns a copy.

The future `send` returns completes when the message is handed to the transport. While more than 1 MiB waits to be sent, further messages are held in Java, and their futures complete only as the backlog shrinks. A loop that waits for each send therefore slows down to what the network carries, instead of filling native memory:

```java
for (byte[] chunk : chunks) {
    channel.send(chunk).get();               // from a thread that is not an event thread
}
```

A sender that does not wait still cannot fill Java memory: each channel holds at most 16 MiB waiting to be sent, and a `send` beyond that fails at once with a `ChannelFullException`. `PeerOptions.builder().channelSendLimit(bytes)` changes the limit for every channel of a peer. `bufferedAmount()` tells how much a channel holds.

Received messages are bounded as well. WebRTC cannot be told to stop receiving, so messages that arrive faster than the handlers take them, or while a channel has no handler, wait in Java. Each channel lets at most 16 MiB of them wait. When more arrive, the channel closes instead of dropping messages unnoticed: the peer's `onError` handlers hear why, the messages after the one over the limit are not delivered, and both sides see the channel close. `PeerOptions.builder().channelReceiveLimit(bytes)` changes the limit. A handler that takes long should hand the work to a thread of its own.

`ChannelOptions` set how a channel delivers:

```java
DataChannel positions = peer.createChannel("positions", ChannelOptions.builder()
        .ordered(false)
        .maxRetransmits(0)
        .build());
```

`negotiatedId(int)` creates a channel both peers set up themselves with the same id, without announcing it; such a channel never shows up in `onChannel`. The [Data Channels](/guide/data/data-channels) guide explains the options in detail.

## WHIP and WHEP

Media servers and streaming services take streams with the WebRTC HTTP Ingestion Protocol ([WHIP](https://www.rfc-editor.org/rfc/rfc9725)) and hand them out with its egress counterpart, WHEP. Both exchange one offer and one answer over HTTP. `Whip` publishes tracks, and `Whep` receives a stream:

```java
Whip.Session live = Whip.to(URI.create("https://example.com/live/whip"))
        .bearerToken(streamKey)
        .track(rtc.media().camera())
        .track(rtc.media().microphone())
        .publish(rtc)
        .get(15, TimeUnit.SECONDS);

Whep.Session viewer = Whep.from(URI.create("https://example.com/live/whep"))
        .play(rtc)
        .get(15, TimeUnit.SECONDS);

viewer.peer().onTrack(track -> System.out.println("Receiving " + track.kind()));
```

By default, the offer goes to the endpoint at once, before any candidate is gathered, and the future completes once the peer is connected. As RFC 9725 asks, the peer bundles all media on one transport (`max-bundle`), the descriptions it sends mark each m-line `rtcp-mux-only`, and a WHIP offer only sends (`sendonly`). If the endpoint refuses the offer, or the peer does not connect within the timeout (15 seconds by default), the future fails, and the session is deleted on the server again. An endpoint that is busy, with status 503, or a WHEP endpoint whose stream has yet to start, with status 409, gets the offer again after the wait its `Retry-After` names, at least a second, as long as that fits within the timeout; without `Retry-After` the session fails at once. A WHEP endpoint may also reject the offer and counter it with one of its own, with status 406: `Whep` then takes its offer back, answers the endpoint's, receiving only, and sends the answer by `PATCH` to the session the endpoint named, before the counter-offer's `valid-until`, 30 seconds by default. The answer rejects the audio or video of the counter-offer that `receive(...)` leaves out, and the session fails if the counter-offer holds none of what it asks for. Data channels of a counter-offer are accepted. Cancelling the future, say once `get` gave up waiting, ends the session the same way. `onTrack` hands over tracks that already arrived, so a handler added once the future completed misses none.

With the default `Gathering.TRICKLE`, only once the endpoint answered does the peer gather its candidates, with the STUN and TURN servers the endpoint names in `Link` headers with `rel="ice-server"`, unless `iceServer(...)` or `turnServer(...)` names servers of its own, which take precedence. The candidates follow the offer by `PATCH` to the session, as `application/trickle-ice-sdpfrag`, a few at a time and with the session's `ETag` as `If-Match`, as RFC 9725 describes. An endpoint that takes no such `PATCH`, with status 405, 415, 422 or 501, gets no more; it may still learn peer-reflexive candidates from incoming connectivity checks, but that does not cover every network topology. A 412 or 428 for the current ICE generation reaches the error handler and stops candidate PATCHes; responses to a generation replaced by a restart are ignored.

For an endpoint without Trickle ICE, use `.gathering(Gathering.COMPLETE)` to include gathered candidates in the initial POST. It waits at most three seconds for gathering, within the overall session timeout, then sends the candidates available so far; any later candidates may still follow by PATCH. Configure STUN/TURN servers locally in this mode, since the endpoint's Link headers arrive after gathering. For example:

```java
Whep.Session viewer = Whep.from(endpoint)
        .turnServer("turn:turn.example.com:3478", turnUsername, turnPassword)
        .gathering(Gathering.COMPLETE)
        .play(rtc)
        .get(15, TimeUnit.SECONDS);
```

Both builders support these methods. `turnServer` is separate from `iceServer(String...)`, so passing three URLs to `iceServer` still means three URLs. The gathering setting controls the initial POST only; ICE restarts continue to use PATCH and trickled candidates.

By default, the offer holds every codec WebRTC has, in WebRTC's order, and the endpoint picks; one that follows the offer's order picks VP8 for video. Endpoints that pass the stream on unchanged, to HLS or a recording, often take only H.264 and Opus. `videoCodecs(...)` and `audioCodecs(...)` offer only the codecs named, in that order of preference, by name (`H264`, `VP8`, `VP9`, `AV1`, `opus`, `G722`, ...) or MIME type (`video/H264`), whatever the case. Retransmission, redundancy, FEC, comfort noise and DTMF go along. For WHEP, they decide what the endpoint sends, also in the answer to a counter-offer. A name WebRTC has no codec of fails the session with a `ConfigurationException` before the offer goes out.

Some endpoints, Amazon IVS among them, refuse a WHIP offer without video, even for audio alone. `alwaysOfferVideo()` then adds video that only sends, in the stream of the tracks, without a track, so that nothing is sent on it; with a video track, it changes nothing:

```java
Whip.Session live = Whip.to(URI.create("https://example.com/live/whip"))
        .bearerToken(token)
        .videoCodecs("H264")
        .audioCodecs("opus")
        .track(rtc.media().microphone())
        .alwaysOfferVideo()
        .publish(rtc)
        .get(15, TimeUnit.SECONDS);
```

`simulcast()` sends the video track of a WHIP session in three layers, as simulcast (RFC 8853), in full, half and quarter resolution, with the rids `f`, `h` and `q`. The offer negotiates them, as RFC 9725 asks, and an endpoint that forwards the stream can then give each viewer the layer its bandwidth takes. `simulcast(Encoding...)` takes two or three layers of its own, the most WebRTC sends, from the largest down, with their resolution factor, bitrate and frame rate. An endpoint that takes no simulcast leaves it out of its answer and gets the first layer only, which is why the largest comes first. Simulcast needs a video track; `Sender.configure` on its sender changes every layer alike.

```java
Whip.Session live = Whip.to(URI.create("https://example.com/live/whip"))
        .bearerToken(token)
        .track(rtc.media().camera())
        .simulcast(
                Encoding.builder().maxBitrate(2_500_000).build(),
                Encoding.builder().scaleDownBy(2).maxBitrate(800_000).build(),
                Encoding.builder().scaleDownBy(4).maxBitrate(200_000).build())
        .publish(rtc)
        .get(15, TimeUnit.SECONDS);
```

A normal SDP answer must have status 201, a nonempty session `Location`, and Content-Type `application/sdp` (parameters are allowed). Invalid responses fail the session; a resource already named by the server is deleted even when its SDP response cannot be used. The message of a failure an endpoint answered with an error names the status, and what the endpoint says of it: the title and detail of problem details (RFC 9457, `application/problem+json`), as in `status 403: Forbidden: The stream key has expired`, or else the start of the body.

When the connection fails, or stays disconnected for two seconds, the session restarts ICE: the peer gathers new candidates, and only the new ICE username fragment and password go to the session, by `PATCH` with `If-Match: *`, while the media go on as negotiated. The session's answer, with status 200 and Content-Type `application/trickle-ice-sdpfrag`, brings its own ICE information and a new `ETag`. Its candidate set replaces the old one completely, including when it is empty. Three restarts in a row that do not bring the connection back, or an endpoint that takes no restarts, end the attempts, which the error handler hears of. `session.restartIce()` restarts ICE at once, say after the network changed; a restart that fails leaves the ICE session before as it was. Restarts also work after a WHEP counter-offer; the player keeps its answerer role and updates only the ICE information.

The timeout bounds the requests as a whole, not each read, so a server that sends its answer a byte at a time cannot stretch it. A request still out when the session times out or is cancelled is cut off at once; one still waiting for the head of its answer only from Java 16 on, and before that at its timeout. Once the server has named the session, in the head of its answer, it is deleted all the same; a session the server created for an answer that had not come yet is left for the server to time out.

`session.close()` deletes the session on the server, which frees what the server holds for it, and closes the peer. Both start at once, so a slow server does not keep the peer open, and the deletion takes at most the session's timeout. The tracks a WHIP session published stay open. `session.peer()` is a regular peer, for stats and anything else. A deletion the server refuses or that fails goes to the runtime's error handler, and also fails the future of `closeAsync()`, which completes once both are done; a peer that did not close fails it too.

Endpoints have to be `https` URLs, as WHIP and WHEP ask, except on the machine the application runs on: there plain `http` will do, for a media server beside it, at `localhost`, an address in 127.0.0.0/8 or `::1`. A name that merely resolves to one of them does not count, since it may resolve elsewhere later. The same holds for the session an endpoint names and for where a redirect points.

The credentials of a session, its bearer token or its Basic user and password, go to the endpoint, and along with the deletion to the session the endpoint names in its `Location` header. That session may be on another host, but an `https` endpoint cannot name a plain `http` one. The offer follows redirects with status 307 and 308, as WHIP and WHEP ask of clients, such as those of a load balancer: up to five of them, all within the timeout, and the credentials go along. A redirect from `https` to plain `http` fails the session, and so does one with status 301, 302 or 303, which would turn the offer into a `GET`. The deletion follows no redirect; one fails it with its status.

Servers that ask for a user and password rather than a token, such as MediaMTX, take them by HTTP Basic authentication (RFC 7617): `basicAuth("user", "password")` sends `Authorization: Basic` with both, in UTF-8, on every request of the session, without waiting to be asked. The user must not contain a colon. Of `bearerToken` and `basicAuth`, the one called last counts. MediaMTX also takes them as a token, the way OBS Studio sends them, `bearerToken("user:password")`. Other schemes, such as Digest, are not supported.

Requests go through Java's `HttpClient`, with the default `SSLContext` and the HTTP proxy Java is configured with, the first the default `ProxySelector` names: `-Dhttps.proxyHost` and `-Dhttps.proxyPort`, `-Dhttp.proxyHost`, or `-Djava.net.useSystemProxies=true`. `HttpClient` takes no SOCKS proxies. A proxy that asks for credentials gets Basic ones from the default `Authenticator`, but only for sessions without credentials of their own: `HttpClient` drops the Authorization header of a request once it answers for credentials itself. As in Java, Basic is not used for the tunnel of an `https` request unless `jdk.http.auth.tunneling.disabledSchemes` allows it. Other schemes, such as Digest, NTLM and Negotiate, are not supported.

| Builder method | What it sets |
|---|---|
| `bearerToken(String)` | The token sent as `Authorization: Bearer`; for many services, the stream key. |
| `basicAuth(String, String)` | A user and password sent as `Authorization: Basic`, as MediaMTX takes them; replaces a bearer token, and the other way round. |
| `iceServer(String...)` | A STUN or TURN server without credentials, overriding endpoint-provided servers. |
| `turnServer(String, String, String)` | A TURN URL, username and password, overriding endpoint-provided servers. |
| `gathering(Gathering)` | When the initial offer is sent: `TRICKLE` by default, or `COMPLETE` to gather first. |
| `track(LocalTrack)` | A track to publish, WHIP only. The tracks share one stream, which holds one audio and one video track at most. |
| `receive(MediaKind...)` | What to receive, WHEP only; video and audio by default. |
| `videoCodecs(String...)`, `audioCodecs(String...)` | The only codecs to negotiate, in order of preference; every codec WebRTC has by default. |
| `alwaysOfferVideo()` | Video in the offer even without a video track, WHIP only, for endpoints that refuse an offer without video. |
| `simulcast()`, `simulcast(Encoding...)` | The video track in layers, as simulcast, WHIP only: three by default (`f`, `h`, `q`), or two or three given, the largest first. |
| `sendTransform(RTCEncodedFrameTransformer)` | The [encoded transform](#encoded-transforms) of every frame sent, WHIP only, in place before the offer goes out. |
| `receiveTransform(RTCEncodedFrameTransformer)` | The [encoded transform](#encoded-transforms) of every frame received, WHEP only, in place before the first frame, also for a counter-offer. |
| `timeout(Duration)` | How long the session may take to be connected, and its deletion to be answered. |

## Manual Negotiation

SFUs and other servers dictate an offer and answer exchange of their own. A peer built with `Negotiation.MANUAL` leaves it to the application, through `peer.negotiator()`; `Whip` and `Whep` are built on it. Each step creates and applies a description in one go, and `Gathering.COMPLETE` waits until the description holds all local candidates, for servers that take no trickled candidates. A STUN or TURN server that cannot be reached, as on networks that block UDP, holds up the end of gathering for a long time, so `COMPLETE` waits at most for the peer's gathering timeout and then goes with the candidates it has. This WHEP client does by hand what `Whep` does:

```java
Peer viewer = rtc.createPeer(PeerOptions.builder()
        .negotiation(Negotiation.MANUAL)
        .receive(MediaKind.VIDEO, MediaKind.AUDIO)
        .build());

viewer.onTrack(track -> System.out.println("Receiving " + track.kind()));

viewer.negotiator().createOffer(Gathering.COMPLETE)
        .thenApplyAsync(offer -> http.postSdp(whepUrl, offer.sdp), ioPool)   // blocking HTTP off the event thread
        .thenCompose(sdp -> viewer.negotiator().applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER, sdp)))
        .thenCompose(applied -> viewer.whenConnected())
        .get(10, TimeUnit.SECONDS);
```

| Method | What it does |
|---|---|
| `createOffer()`, `createOffer(Gathering)` | Creates the local offer and applies it. |
| `answer(offer)`, `answer(offer, Gathering)` | Applies the remote offer, then creates the answer and applies it. |
| `applyAnswer(answer)` | Applies the remote answer to the local offer. |
| `addCandidate(candidate)` | Adds a remote candidate, or holds it until there is a remote description to add it to. |

A manual peer with a `Signaling` still sends its local candidates through it. `peer.negotiator()` throws an `IllegalStateException` on a peer that negotiates automatically.

## Statistics

`peer.stats()` returns the numbers people look for first, taken from the full [RTC stats](/guide/monitoring/rtc-stats) report:

```java
peer.onStats(Duration.ofSeconds(1), stats -> System.out.printf(
        "%s candidate, rtt %s, %d kbit/s out, %d kbit/s in, %.1f%% lost%n",
        stats.candidateType(), stats.roundTripTime(),
        stats.outgoingBitrate() / 1000, stats.incomingBitrate() / 1000,
        stats.packetLoss() * 100));
```

| Method | Value |
|---|---|
| `candidateType()` | The type of the local candidate in use: `host`, `srflx`, `prflx` or `relay`. Null while not connected. |
| `roundTripTime()` | The current round trip time, or null while not measured. |
| `bytesSent()`, `bytesReceived()` | The bytes sent and received on the connection so far. |
| `timestamp()` | When WebRTC measured the statistics. |
| `outgoingBitrate()`, `incomingBitrate()` | Bits per second between the measurement times of the previous sample and this one, or -1 when there is none to compare with: for a peer's first sample, after the clock went back, or after the byte counter started again. A report WebRTC hands out again within its short cache lifetime repeats the previous bitrates. |
| `packetLoss()` | The share of received packets lost so far, 0 to 1. |
| `outboundVideo()`, `inboundVideo()` | Codec, resolution and frame rate of each video stream, and what limits a sent one. |
| `report()` | The whole `RTCStatsReport`. |

## Closing

You close what you created, and closing an owner closes what it owns:

| Object | Closing it also |
|---|---|
| `WebRtc` | closes all peers, then all tracks, then the factory, and stops the threads of the client API |
| `Peer` | closes its channels and senders and ends its remote tracks. Local tracks stay open for other peers. |
| `LocalTrack` | stops sending it on every peer and stops its capture |
| `DataChannel` | fails the messages not yet handed to the transport |
| `Subscription` | removes the handler |

`Peer.close()` and every other `close()` except the runtime's return at once, may be called more than once, and are fine inside a handler. After a peer closed, its only event is the `CLOSED` state. Operations that were pending fail with a `PeerClosedException`, and so do those called later. `closeAsync()` returns a future for callers that want to wait.

`WebRtc.close()` is the one close that waits, for up to the close timeout, which makes it fit for try-with-resources. Once it returns, every peer's `state()` is `CLOSED`; the `whenClosed` futures and `CLOSED` events follow on the peers' event threads, since waiting for them could never end when `close()` is called on an event thread.

When a peer or track does not close within the close timeout, or a track or its source could not be released, the runtime leaves its factory undisposed rather than free what WebRTC's threads may still use. A track's `closeAsync()` fails in that case too. `WebRtc.closeAsync()` then fails with a `WebRtcException` naming the cause, and the error handler hears of it as well, which is how `close()` reports it.

## Errors

Failures arrive as exceptions, thrown or as the cause of a failed future, all of them a `WebRtcException`:

| Exception | When |
|---|---|
| `NegotiationException` | A description or candidate was rejected, or two impolite peers collided. |
| `PeerClosedException` | An operation was pending when its peer, channel or runtime closed, or came after that. |
| `ChannelFullException` | A data channel already holds as many bytes waiting to be sent as its peer's `channelSendLimit` allows. |
| `AudioQueueFullException` | Pushed audio would take a `CustomAudio` queue beyond its `queueLimit`, with `AudioOverflow.REJECT`. |
| `MediaException` | A device is missing, busy, refused access, or already open with other options. |
| `ConfigurationException` | A call does not fit how the runtime was built, or WebRTC refused a configuration. |
| `BlockingCallException` | A future was waited on from an event thread. |

Failures that no caller waits for, such as a remote description rejected during automatic negotiation, go to the peer's `onError` handlers:

```java
peer.onError(error -> log.warn("Peer {} reported", peer.name(), error));
```

Without an `onError` handler, they go to the runtime's error handler, as do exceptions that handlers throw. A handler that throws never stops the delivery of later events.

## Using the Low-Level API

For what the client API does not cover, the underlying objects stay reachable:

```java
// Anything RTCConfiguration offers.
PeerOptions.builder().configure(config -> config.bundlePolicy = RTCBundlePolicy.MAX_BUNDLE);

// Anything the factory builder offers.
WebRtc.builder().configureFactory(factory -> factory.setVideoEncoderFactory(myEncoders));

// The connection itself, in order with the peer's other operations.
peer.withNative(connection -> connection.getSignalingState())
        .thenAccept(state -> System.out.println("Signaling state: " + state));
```

The function passed to `withNative` runs after the operations called before it and before those called after it, so it never interferes with negotiation. It must not block. Objects it obtains from the connection belong to it, as in the low-level API, and must not be used after it returns.

## Example

The [`ClientApiExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/ClientApiExample.java) connects two peers in one process through a signaling that carries JSON text. It sends pushed video, audio and chat messages from one to the other, and prints statistics once a second. It needs no audio or video hardware:

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.ClientApiExample" -D"exec.args=10"
```
