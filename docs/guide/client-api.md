# Client API

The client API in `dev.onvoid.webrtc.client` is a layer over the rest of the library. It takes care of the parts most applications get wrong: threads, the offer and answer exchange, and the lifetime of native objects. A whole video call fits on one screen:

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

The client API is plain Java on top of the classes the other guides describe, and it does not replace them. Its peers, tracks and channels are the same native objects, and [Using the Low-Level API](#using-the-low-level-api) shows how to reach them for anything it does not cover. [Client API Design](/guide/client-api-design) explains the decisions behind it.

## Pages of This Guide

The client API is described on these pages:

| Page | What it covers |
|---|---|
| [Peers and Signaling](/guide/client/peers) | Creating peers, carrying their messages, connection state, ICE recovery and manual negotiation. |
| [Threads](/guide/client/threads) | Where handlers run, limiting threads, the UI thread, and why a handler must not wait. |
| [Sending Media](/guide/client/sending-media) | Cameras, microphones, screens, files, pushed audio and video, senders, DTMF and devices. |
| [Receiving Media](/guide/client/receiving-media) | Remote tracks, video frames, video views for Swing and JavaFX, audio and recording. |
| [Transforms and Encryption](/guide/client/transforms) | Encoded transforms and end-to-end encryption of a peer's media. |
| [Data Channels](/guide/client/data-channels) | Messages between peers, flow control and channel options. |
| [WHIP and WHEP](/guide/client/whip-whep) | Publishing to and playing from media servers. |
| [Statistics and Call Checks](/guide/client/statistics) | Connection statistics, diagnosis, and checking devices and network before a call. |

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
| `eventExecutor(Executor)` | threads of the runtime | Where handlers run, see [Threads](/guide/client/threads). |
| `eventThreads(int)` | unlimited | The most threads the runtime runs handlers on, see [Limiting Threads](/guide/client/threads#limiting-threads). |
| `frameThreads(int)` | unlimited | The most threads that run `onFrame` and `onAudio` handlers. |
| `audioBuffer(Duration)` | 5 seconds | How much audio an `onAudio` subscription keeps for a handler that falls behind, see [Audio](/guide/client/receiving-media#audio). |
| `errorHandler(Consumer<Throwable>)` | the thread's uncaught exception handler | Receives what handlers throw, and failures nobody else hears of. |
| `operationTimeout(Duration)` | 30 seconds | How long an operation such as applying a description may take before it fails with a `TimeoutException`. |
| `closeTimeout(Duration)` | 10 seconds | How long `close()` waits. |
| `configureFactory(Consumer<PeerConnectionFactory.Builder>)` | none | Anything else of the factory, such as codec factories of your own. |

The timeouts are described under [Details](#timeouts).

### Audio Modes

The runtime's `AudioMode` decides whether it may open the microphone and where it plays remote audio. Every mode sends audio pushed through `CustomAudio`; each sender gets the audio of its own track, so a `DEVICES` runtime can send the microphone and pushed audio side by side, also on one peer:

| Mode | Sends | Plays remote audio | Use it for |
|---|---|---|---|
| `DEVICES` | the microphone and audio pushed through `CustomAudio` | to the speakers | calls on a desktop |
| `CUSTOM` | audio pushed through `CustomAudio`; the microphone is never opened | to the speakers | applications that generate or relay audio |
| `HEADLESS` | audio pushed through `CustomAudio` | to `RemoteAudio.onAudio` handlers only | servers, bots and tests, without any audio hardware |

Asking a `CUSTOM` or `HEADLESS` runtime for the microphone fails at once with a `ConfigurationException` that names the mode, rather than when the first track is added. The microphone is opened only while one of its tracks is sent.

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

A close that fails is described under [Details](#a-close-that-fails).

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
| `HttpStatusException` | A WHIP or WHEP endpoint answered with an error status: `status()`, and the `title()`, `detail()` and `problemType()` of its problem details, if any. |

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

The [`ClientApiExample`](https://github.com/devopvoid/webrtc-java/blob/main/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/ClientApiExample.java) connects two peers in one process through a signaling that carries JSON text. It sends pushed video, audio and chat messages from one to the other, and prints statistics once a second. It needs no audio or video hardware:

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.ClientApiExample" -D"exec.args=10"
```

## Details

### Timeouts

Every timeout of the client API must be positive; none of them can be switched off. This holds for the operation and close timeouts, the [gathering timeout](/guide/client/peers#manual-negotiation), and the timeouts of [`Whip` and `Whep`](/guide/client/whip-whep).

A timeout counts in milliseconds, rounded up, so one under a millisecond waits a millisecond. One too long for milliseconds to count waits as long as they can count, about 292 million years.

### A Close That Fails

A peer or track may not close within the close timeout, or a track or its source may not be released. The runtime then leaves its factory undisposed, rather than free what WebRTC's threads may still use.

- A track's `closeAsync()` fails in that case too.
- `WebRtc.closeAsync()` fails with a `WebRtcException` naming the cause.
- The error handler hears of it as well, which is how `close()` reports it.
