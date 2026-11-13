# Receiving Media

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

## Video Frames

Each `onFrame` subscription has a thread of its own, so a slow renderer holds up neither WebRTC's decoder nor the peer's other events. With `frameThreads(n)`, the subscriptions share `n` threads and take turns (see [Limiting Threads](/guide/client/threads#limiting-threads)).

By default, the handler always gets the newest frame, and the frames it had no time for are dropped. `FrameDelivery.queue(n)` keeps up to `n` frames instead:

```java
video.onFrame(FrameDelivery.queue(10), frame -> encoder.encode(frame));
```

A frame is valid while the handler runs. Call `frame.retain()` to keep it longer, and `frame.release()` when done.

## Showing Video

Handlers that touch UI components need the toolkit's thread as event executor, see [Handlers on the UI Thread](/guide/client/threads#handlers-on-the-ui-thread).

The `webrtc-java-swing` module has a `VideoView` in `dev.onvoid.webrtc.client.swing` that shows video in a Swing window. The core `webrtc-java` module does not depend on `java.desktop`, so headless applications and trimmed runtime images need neither AWT nor Swing:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java-swing</artifactId>
    <version>{{ VERSION }}</version>
</dependency>
```

The view converts each frame on the frame's own thread and leaves only the painting to the EDT. It keeps the picture's aspect ratio and rotation, and can mirror a local camera preview:

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

`snapshot()` returns a copy of the picture shown, for saving a still or for tests. How much memory the views take is described under [Details](#video-views-and-memory).

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

## Audio

`onAudio` hands decoded audio to a handler in order, usually 10 ms of 16-bit PCM per `AudioChunk`. Audio reaches these handlers only while the runtime plays out. A `HEADLESS` runtime always does; a `DEVICES` or `CUSTOM` runtime plays out to its speakers. `setVolume(double)` changes the playout gain of a track, from 0 to 10, where 1 plays it as received.

A handler that falls behind gets the audio of the last 5 seconds. The oldest audio beyond that is dropped, and counted in `rtc.loadStats().audio().dropped()`. `WebRtc.Builder.audioBuffer(Duration)` changes the buffer of every subscription, and `onAudio(Duration, handler)` that of one.

A speech recognizer that must stay live keeps a short buffer. A recorder that can catch up after a stall keeps a long one, at about 190 KiB per second of 48 kHz stereo:

```java
track.asAudio().onAudio(Duration.ofMillis(200), chunk -> recognizer.feed(chunk.samples()));
```

A local audio track tells when this side speaks. `onVoiceActivity` calls its handler with `true` when speech begins and `false` when it ends, on the event executor. The audio is analyzed in native code, and only these changes reach Java:

```java
LocalAudio microphone = rtc.media().microphone();
Subscription speaking = microphone.onVoiceActivity(
        new VoiceActivityConfig(0.6, Duration.ofMillis(400)),
        active -> System.out.println(active ? "speaking" : "quiet"));
```

`VoiceActivityConfig` sets two values:

- the threshold: the voice probability, from 0 to 1, at which audio counts as speech (0.5 by default),
- the hangover: how long speech goes on once the audio fell below the threshold (300 ms by default), which bridges the pauses between words.

Which audio is analyzed is described under [Details](#speech-detection).

## Receiving Without Sending

A viewer that sends nothing asks for what it wants to receive, so that the offer includes it:

```java
Peer viewer = rtc.createPeer(PeerOptions.builder()
        .receive(MediaKind.VIDEO, MediaKind.AUDIO)
        .signaling(signaling)
        .build());
```

## Recording

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

## Details

### Video Views and Memory

Both views support resolutions such as 8K, without a fixed pixel limit. They reject nonpositive dimensions, and frames whose four-byte pixel data cannot fit in a Java buffer, using overflow-safe arithmetic.

- Conversion buffers are allocated on demand, grow geometrically when necessary, and keep their capacity across smaller frames and `clear()`.
- Swing reuses one direct buffer; JavaFX reuses up to two.
- JavaFX reserves a buffer only while copying it into the displayed image. A waiting frame may be replaced by a newer one, so that no queue builds up.
- Buffer memory is released when the view becomes unreachable.

Memory use follows the largest frames received. It includes displayed images, conversion buffers, and replacement allocations awaiting garbage collection. Snapshots, input frames and toolkit rendering resources use additional memory.

### Speech Detection

Pushed audio is analyzed as pushed.

A microphone's audio is analyzed after audio processing, with echo cancellation, noise suppression and gain control applied as configured. The remote peer's voice from the speakers therefore does not count as this side speaking.

The microphone's audio flows while WebRTC records, which it does while a peer sends a microphone track. Every microphone track of a runtime hears the same, as WebRTC records one microphone for all of them.