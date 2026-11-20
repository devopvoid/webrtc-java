# LiveKit Rooms

The `webrtc-java-livekit` module joins rooms of a [LiveKit](https://livekit.io) server. Each participant publishes its tracks once, and the server forwards them to everyone else in the room:

```java
Room room = LiveKit.room(rtc, URI.create("wss://live.example.com"), token)
        .connect()
        .get(15, TimeUnit.SECONDS);

room.onTrackSubscribed((participant, track) ->
        System.out.println("Receiving " + track.kind() + " of " + participant.identity()));

room.publish(rtc.media().camera(), TrackSource.CAMERA);
room.publish(rtc.media().microphone(), TrackSource.MICROPHONE);
```

The module builds on the [Client API](/guide/client-api): tracks are the runtime's local and remote tracks, and the room holds two of its peers.

::: warning
The module is new. Its API may still change between releases.
:::

## Adding the Module

Depend on the module beside `webrtc-java`. It brings `protobuf-java` along, which carries LiveKit's signaling messages:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java-livekit</artifactId>
    <version>{{ VERSION }}</version>
</dependency>
```

Its module name is `webrtc.java.livekit`. The module has no natives of its own.

## Joining a Room

`LiveKit.room(rtc, url, token)` takes the server's URL and an access token for one room. `connect()` opens the signaling connection and joins. Its future completes once the room is joined.

The token is a JWT that your backend signs with the server's API key and secret. It names the participant's identity, the room, and what the participant may do there. Never ship the API secret in a client. LiveKit's [server SDKs](https://docs.livekit.io/home/server/generating-tokens/) create tokens in a few lines.

The future fails with a `LiveKitException` if the server refuses the token or does not answer in time. The message names the HTTP status and what the server said. The timeout is ten seconds by default:

```java
Room room = LiveKit.room(rtc, url, token)
        .timeout(Duration.ofSeconds(5))
        .connect()
        .get(15, TimeUnit.SECONDS);
```

## Participants

`localParticipant()` is this client, and `remoteParticipants()` lists everyone else. A `Participant` has the server's `sid()`, the `identity()` from the token, a display `name()`, and the `metadata()` and `attributes()` your backend gave it:

```java
room.onParticipantJoined(participant -> System.out.println(participant.identity() + " joined"));
room.onParticipantLeft(participant -> System.out.println(participant.identity() + " left"));
```

The participant objects stay the same while their fields change. A participant that leaves is reported once.

## Publishing Tracks

`publish(track, source)` publishes any local track: a camera, a microphone, a shared screen or a pushed track. The server first accepts the track, then the room negotiates it. The future holds a `Publication`:

```java
Publication publication = room.publish(rtc.media().microphone(), TrackSource.MICROPHONE)
        .get(15, TimeUnit.SECONDS);

System.out.println("Published as " + publication.sid());
```

`publication.sid()` is the id the other participants see the track under. The track stays yours: close it after you left the room.

### Simulcast

With simulcast, a video track is sent in several sizes at once. The server forwards each subscriber the one its connection and view take. `PublishOptions` turns it on, and needs the size the video is captured in:

```java
Publication publication = room.publish(camera, PublishOptions.builder()
        .source(TrackSource.CAMERA)
        .videoSize(1280, 720)
        .simulcast(true)
        .build())
        .get(15, TimeUnit.SECONDS);
```

The room picks the layers the way LiveKit's own clients do:

| Captured height | Layers | Example for 1280x720 |
|---|---|---|
| 720 lines or more | `q` at a quarter, `h` at half, `f` at full size | 320x180, 640x360, 1280x720 |
| 360 to 719 lines | `q` at half, `h` at full size | - |
| Below 360 lines | None: the track is sent in one size | - |

The server pauses the layers that no subscriber watches, and resumes them once one does (LiveKit's dynacast). `publication.rids()` lists the layers. The bitrates are those of LiveKit's presets, listed under [Details](#simulcast-bitrates).

## Receiving Tracks

By default, the room receives every track published in it. `onTrackSubscribed` hands each one over with the participant that publishes it. Tracks that arrived before the handler was added are handed to it too:

```java
room.onTrackSubscribed((participant, track) -> {
    if (track.isVideo()) {
        track.asVideo().onFrame(frame -> System.out.println(
                participant.identity() + ": " + frame.buffer.getWidth() + "x" + frame.buffer.getHeight()));
    }
});
```

The track is a regular remote track, as described in [Receiving Media](/guide/client/receiving-media). Its `id()` is the publication's sid.

A video sent in simulcast arrives in the largest layer the connection takes. A small view, such as a thumbnail, needs no more than the smallest layer:

```java
room.requestVideoQuality(track.id(), VideoQuality.LOW);
```

`LOW`, `MEDIUM` and `HIGH` are the layers `q`, `h` and `f`. The server still forwards a smaller layer while the connection takes no more.

## Sending Data

`publishData(payload, topic, reliable)` sends bytes to every other participant. The topic lets receivers tell messages apart:

```java
room.publishData("slide 12".getBytes(StandardCharsets.UTF_8), "slide", true);

room.onData(message -> {
    if (message.topic().orElse("").equals("slide")) {
        showSlide(new String(message.payload(), StandardCharsets.UTF_8));
    }
});
```

- **Reliable** (`true`): ordered and retransmitted, for events that must arrive, such as the slide a presentation is on.
- **Lossy** (`false`): sent once, unordered, for data that is stale soon, such as a pointer position.

A `DataMessage` also names the `senderIdentity()` and whether it came reliably. The future of `publishData` completes once the data is handed to the channel.

## Reconnecting

If the connection is lost, the room gets it back by itself. The application hears of it through two handlers:

```java
room.onReconnecting(reason -> System.out.println("Reconnecting: " + reason));
room.onReconnected(() -> System.out.println("Reconnected"));
```

1. The room first resumes the session. The participant, its peers and its tracks stay as they are.
2. If the server cannot resume it, the room joins anew and publishes its tracks again.
3. If ten attempts fail, the room is left, and `onDisconnected` gets the reason.

While the room reconnects, `publish` and `publishData` wait until it is connected again. After a new join, the room behaves as if everyone had left and joined again:

- The local participant has a new sid.
- `onParticipantLeft` and then `onParticipantJoined` report every other participant.
- Each `Publication` keeps its object, but has a new sid.
- The remote tracks arrive anew through `onTrackSubscribed`.

`state()` tells where the room is:

| State | Meaning |
|---|---|
| `CONNECTING` | Joining the room. |
| `CONNECTED` | Joined, media and data flow. |
| `RECONNECTING` | The connection was lost, and the room is getting it back. |
| `DISCONNECTED` | The room was left, and cannot be joined again. |

## Threads

The room runs its handlers on a thread of its own, one at a time, in the order the server sent what they report. Its futures complete on that thread too.

A handler that blocks holds up the whole room. Long work belongs on another thread. In particular, never wait for a room's future inside a handler of the same room: the future can only complete once the handler returned.

## Leaving a Room

`room.close()` leaves the room and waits until its peers are closed. `closeAsync()` returns a future instead. `onDisconnected` gets the reason, whether the application left, the server ended the session, or reconnecting failed.

The tracks stay open: they are the application's, and may still be sent elsewhere.

## Details

### Simulcast Bitrates

Each layer gets the bitrate of LiveKit's preset for its height:

| Layer height | Bitrate |
|---|---|
| 1080 lines or more | 3 Mbit/s |
| 720 to 1079 lines | 1.7 Mbit/s |
| 540 to 719 lines | 800 kbit/s |
| 360 to 539 lines | 450 kbit/s |
| Below 360 lines | 160 kbit/s |

### What Starts a Reconnect

- The signaling connection closes.
- The server stops answering pings, within the timeout it named at the join.
- One of the two peers fails.
- The server asks for it, with a leave request that allows a resume or a new join.

The first attempt starts at once. The attempts after it wait 300 ms times the square of their number, at most ten seconds. Each attempt may take twice the room's timeout. A resume that fails turns the next attempt into a new join.

If the server sends a new token, the room uses it for later attempts.

### Limits

- With `autoSubscribe(false)`, the room receives no tracks. The module has no call yet to subscribe to single tracks.
- A track that is being published while the room joins anew fails, instead of being published in the new session. Published tracks are published again.
- The ICE servers the server names when a session resumes are not applied. The peers keep those of the join.
- The module speaks LiveKit's signaling protocol 16. It is tested against `livekit-server` 1.13.
- End-to-end encryption, LiveKit's RPC and text and byte streams are not supported.

## Example

The [`LiveKitExample`](https://github.com/devopvoid/webrtc-java/blob/main/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/LiveKitExample.java) joins a room, publishes a test pattern in simulcast and sends an event every five seconds. Start a server with `livekit-server --dev`, then run the example twice:

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.LiveKitExample" -D"exec.args=ws://localhost:7880 lecture alice 30"
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.LiveKitExample" -D"exec.args=ws://localhost:7880 lecture bob 30"
```

## Related

- [Sending Media](/guide/client/sending-media) - The local tracks a room publishes
- [Receiving Media](/guide/client/receiving-media) - Frames, views and audio of remote tracks
- [WHIP and WHEP](/guide/client/whip-whep) - Publishing to and playing from media servers without rooms
