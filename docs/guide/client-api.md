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
| `errorHandler(Consumer<Throwable>)` | the thread's uncaught exception handler | Receives what handlers throw, and failures nobody else hears of. |
| `operationTimeout(Duration)` | 30 seconds | How long an operation such as applying a description may take before it fails with a `TimeoutException`. |
| `closeTimeout(Duration)` | 10 seconds | How long `close()` waits. |
| `configureFactory(Consumer<PeerConnectionFactory.Builder>)` | none | Anything else of the factory, such as codec factories of your own. |

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

`SignalMessage.fromJson(String)` reads these, what a browser's `JSON.stringify(pc.localDescription)` and `JSON.stringify(candidate)` write, and the `{"description": ...}` and `{"candidate": {...}}` envelopes of the perfect negotiation examples. Routing, such as room names or peer ids, belongs in an envelope of your own around this JSON.

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
| `onFrame` and `onAudio` handlers | a thread of each subscription | Better not. A slow video handler gets the newest frame next, and the frames in between are dropped. |
| `LocalTrack.onEnded` and `Devices.onChange` handlers | a background thread of the runtime | Briefly. |
| WebRTC's own callbacks | WebRTC's threads | Not your code. The library only copies what they report and queues it. |

The handlers of one peer never run at the same time, and they run in the order of the events. The handlers of different peers may run at the same time. With the default executor, the event thread is named after its peer, such as `webrtc-client-events-alice`, which shows up in thread dumps.

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

The view converts frames on their own thread and paints at most once per JavaFX pulse. `setMirrored(true)`, or its `mirroredProperty()`, flips a camera preview, and `snapshot()`, on the JavaFX Application Thread, returns a copy of the picture.

### Don't Wait in a Handler

A future of the client API completes on the event thread of its peer. A handler that waits for one would wait for its own thread, so `join()` and `get()` there throw a `BlockingCallException` instead of hanging. Chain the next step instead:

```java
peer.onChannel(channel -> {
    // channel.whenOpen().join() would throw here.
    channel.whenOpen().thenCompose(open -> open.send("hello"));
});
```

A future that is already done may be read anywhere, and any thread that is not an event thread, such as `main`, may wait.

::: warning
On Java 8, only the futures the API returns refuse to be waited on. Futures derived from them with `thenApply` and the like do not, so waiting for one of those in a handler hangs that peer. From Java 9 on, the derived futures refuse too.

A UI thread used as event executor is the same: waiting on it for a future of this API hangs the UI, since the future completes on it.
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

Create the runtime before the file: the runtime loads the native library that the file's sources need. The sources belong to the `MediaFileSource`, not to the tracks, so close the runtime, or the tracks, before the file. The file's audio source is pushed audio, so the runtime needs the `CUSTOM` or `HEADLESS` audio mode.

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

`CustomAudio` takes PCM in pieces of any length and from any thread. It cuts the audio into the 10 ms chunks WebRTC works with and sends one every 10 ms from a thread of its own, so pushing a whole second at once is as fine as pushing every 10 ms. `queuedMillis()` tells how much is still waiting, and `clear()` drops it.

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

The new camera keeps the resolution and frame rate of the old one, as far as it supports them. The switch opens the new camera before it closes the old one, so that the peers see no gap. Some devices cannot capture with two cameras at once, such as the two sensors of a laptop camera; then the old camera closes first, and the switch takes about half a second. If the new camera cannot be opened, the future fails with a `MediaException`, and the track keeps sending the camera it had.

### Devices

`media.devices()` returns the cameras, microphones and speakers. The first call reads them from the system; after that, the lists are snapshots kept current in the background, so reading them is cheap on any thread. `screens()` and `windows()` ask the system on each call.

```java
Devices devices = media.devices();

devices.onChange(changed -> System.out.println("Cameras: " + changed.cameras()));
devices.useMicrophone(devices.microphones().get(1));
devices.useSpeaker(devices.speakers().get(0));
```

`useMicrophone` and `useSpeaker` switch devices also for tracks that are sent or played now.

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

Each `onFrame` subscription has a thread of its own, so a slow renderer holds up neither WebRTC's decoder nor the peer's other events. By default the handler always gets the newest frame, and the frames it had no time for are dropped. `FrameDelivery.queue(n)` keeps up to `n` frames instead:

```java
video.onFrame(FrameDelivery.queue(10), frame -> encoder.encode(frame));
```

A frame is valid while the handler runs. Call `frame.retain()` to keep it longer, and `frame.release()` when done.

### Audio

`onAudio` hands decoded audio to a handler in order, usually 10 ms of 16-bit PCM per `AudioChunk`. Audio reaches these handlers only while the runtime plays out, which a `HEADLESS` runtime always does and a `DEVICES` or `CUSTOM` runtime does to its speakers. `setVolume(double)` changes the playout gain of a track, from 0 to 10, where 1 plays it as received.

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
- Messages that arrive while a channel has no `onMessage` handler are kept for the next one, so none are lost while a handler is being set up. Up to 16 MiB are kept; beyond that the oldest are dropped, and the peer's `onError` handlers are told.
- `send` takes a `String`, a `byte[]` or a `ByteBuffer`, and copies the data before it returns.
- A `Message` holds a copy of the data. `isText()` tells text from binary, `text()` decodes it as UTF-8, and `bytes()` returns a copy.

The future `send` returns completes when the message is handed to the transport. While more than 1 MiB waits to be sent, further messages are held in Java, and their futures complete only as the backlog shrinks. A loop that waits for each send therefore slows down to what the network carries, instead of filling native memory:

```java
for (byte[] chunk : chunks) {
    channel.send(chunk).get();               // from a thread that is not an event thread
}
```

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

The offer goes to the endpoint with the local candidates, as WHIP and WHEP servers expect, and the future completes once the peer is connected. If the endpoint refuses the offer, or the peer does not connect within the timeout (15 seconds by default), the future fails, and the session is deleted on the server again. `onTrack` hands over tracks that already arrived, so a handler added once the future completed misses none.

`session.close()` deletes the session on the server, which frees what the server holds for it, and closes the peer. The tracks a WHIP session published stay open. `session.peer()` is a regular peer, for stats and anything else.

| Builder method | What it sets |
|---|---|
| `bearerToken(String)` | The token sent as `Authorization: Bearer`; for many services, the stream key. |
| `iceServer(String...)` | A STUN or TURN server; endpoints usually name their own in the answer. |
| `track(LocalTrack)` | A track to publish, WHIP only. The tracks share one stream. |
| `receive(MediaKind...)` | What to receive, WHEP only; video and audio by default. |
| `timeout(Duration)` | How long the session may take to be connected. |

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
| `outgoingBitrate()`, `incomingBitrate()` | Bits per second since the previous sample, or -1 for a peer's first sample. |
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

## Errors

Failures arrive as exceptions, thrown or as the cause of a failed future, all of them a `WebRtcException`:

| Exception | When |
|---|---|
| `NegotiationException` | A description or candidate was rejected, or two impolite peers collided. |
| `PeerClosedException` | An operation was pending when its peer, channel or runtime closed, or came after that. |
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
