# Sending Media

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
| `customAudio(String)` | Audio you push, in every mode. |
| `videoTrack(String, VideoTrackSource)`, `audioTrack(String, AudioTrackSource)` | A source of the core API, such as one the [media module](/guide/media/media-files) feeds, or a [`SystemAudioSource`](/guide/audio/system-audio-source) for the audio the system plays. The source stays yours. |

```java
Devices devices = media.devices();
LocalVideo screen = media.screen(ScreenOptions.screen(devices.screens().get(0)).frameRate(15).build());
```

A content hint tells the senders of a video track what to keep when the bandwidth or the CPU runs short. It can be changed at any time, also while the track is sent, without renegotiating:

```java
screen.setContentHint(VideoContentHint.TEXT);      // source code: keep the resolution
peer.addTrack(screen);

screen.setContentHint(VideoContentHint.MOTION);    // a video starts in the shared window: keep the frame rate
```

| Hint | Suits | Gives up first |
|---|---|---|
| `DEFAULT` | Anything | What the source suggests: the frame rate of screens, the resolution of other video |
| `MOTION` | Cameras, videos, animations | Resolution |
| `DETAIL` | Drawings, slides, photos | Frame rate |
| `TEXT` | Source code, terminals, tables | Frame rate; WebRTC treats it as `DETAIL` for now |

The hint belongs to the track, so it applies on every peer the track is sent on, and a camera keeps it when it switches. Screens keep their resolution by default already, so the hint pays off where the content changes, or for a camera that shows a whiteboard. No hint keeps text readable at any bitrate.

Tracks added without stream ids share one stream per peer, so that a browser plays their audio and video in sync. Pass ids to `addTrack(track, streamIds...)` to group them yourself.

One track may be sent on any number of peers. Opening a camera that is already open shares its capture when the options are the same, and fails with a `MediaException` when they differ.

## Sending a Media File

The media module plays files and RTSP streams into sources of the core API. `videoTrack` and `audioTrack` put them on peers like any other track:

```java
WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build();   // first: it loads the native library
MediaFileSource file = new MediaFileSource(Paths.get("movie.mp4"));

peer.addTrack(rtc.media().videoTrack("movie", file.getVideoSource()));
peer.addTrack(rtc.media().audioTrack("movie", file.getAudioSource()));
file.play();
```

Create the runtime before the file: the runtime loads the native library that the file's sources need. The sources belong to the `MediaFileSource`, not to the tracks, so close the runtime, or the tracks, before the file: `track.closeAsync()` returns a future that completes once the track let go of its source. The file's audio source is pushed audio, so the runtime needs the `CUSTOM` or `HEADLESS` audio mode.

## Pushing Video and Audio

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

A producer faster than real time would otherwise fill memory and push the delay up without end, so the queue holds at most 10 seconds, and a `push` beyond that fails with an `AudioQueueFullException`, keeping none of its audio. `queueLimit(max, overflow)` sets another limit and what happens beyond it:

```java
// Live audio: stay at most 200 ms behind, dropping the oldest audio.
voice.queueLimit(Duration.ofMillis(200), AudioOverflow.DROP_OLDEST);

// A file, which must arrive whole: refuse the push, to try it again later.
voice.queueLimit(Duration.ofSeconds(2), AudioOverflow.REJECT);
```

With `REJECT` a refused push can be pushed again once `queuedMillis()` has shrunk; with `DROP_OLDEST` every push is taken, and the audio sent lags at most the limit behind.

## Controlling Senders and Tracks

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

The characters 0 to 9, A to D, `#` and `*` are tones, and a comma is a pause of 2 seconds. What happens to queued and stopped tones is described under [Details](#dtmf-tones), and the [DTMF Sender](/guide/audio/dtmf-sender) guide has more.

## Switching Cameras

A `LocalCamera` switches to another camera without renegotiating. Every peer that sends it keeps sending it, and preview handlers keep getting frames:

```java
LocalCamera camera = media.camera();
peer.addTrack(camera);

camera.switchCamera(media.devices().cameras().get(1))
        .thenRun(() -> System.out.println("Now sending " + camera.device().getName()));
```

The new camera keeps the resolution and frame rate of the old one, as far as it supports them. The switch opens the new camera before it closes the old one, so that the peers see no gap. Some devices cannot capture with two cameras at once, such as the two sensors of a laptop camera; then the old camera closes first, and the switch takes about half a second. What happens when a switch fails is described under [Details](#when-a-camera-switch-fails).

## Devices

`media.devices()` returns the cameras, microphones and speakers. The first call reads them from the system; after that, the lists are snapshots kept current in the background, so reading them is cheap on any thread. How the lists are read, and how a query fails, is described under [Details](#device-lists).

```java
Devices devices = media.devices();

devices.onChange(changed -> System.out.println("Cameras: " + changed.cameras()));
devices.useMicrophone(devices.microphones().get(1));
devices.useSpeaker(devices.speakers().get(0));
```

`useMicrophone` and `useSpeaker` switch devices also for tracks that are sent or played now. A device that was disconnected since the list was read fails the returned future, and the device in use stays as it was. On Linux, where WebRTC's audio device module cannot tell devices apart, the default device is used whichever is given.

A track does not end by itself when its device is disconnected or a captured window closes. `LocalTrack.onEnded` follows only from closing the track, or from a camera switch that can open neither camera. `onChange` tells of a device that went away; closing the track, or switching a camera to another device, is up to you. Nothing tells of a captured window that closes.

## Details

### Timing of Pushed Audio

That thread does nothing else, so a slow push into WebRTC delays only the audio, not the timeouts of the runtime. Should it stall, as in a long garbage collection pause, it makes up for at most the last 50 ms instead of sending the whole pause at once; the queued audio then stays behind by the rest, until `clear()` drops it.

### DTMF Tones

Tones still queued from an earlier call are replaced, and that call's future is cancelled. A video sender, or one whose remote peer accepts no telephone events, fails the future with an `IllegalStateException`. So do tones WebRTC stops sending midway, for example because the sender was paused with `configure`, a few seconds after their planned end.

### When a Camera Switch Fails

If the new camera cannot be opened, the future fails with a `MediaException`, and the track keeps sending the camera it had. If a peer does not take the new camera within the operation timeout, the future fails with a `WebRtcException`; the track goes back to the old camera, or keeps the new one when the old one had to close first. A track that closed its camera first and can open neither camera ends.

### Device Lists

Where the system reports no device changes, as on a Linux machine without PulseAudio, they are read on each call instead. `screens()` and `windows()` ask the system on each call. A query that fails, including an unavailable audio backend, throws a `MediaException`, so an empty list means there are no such devices. Its cause is an `AudioBackendUnavailableException` when the audio backend is missing or unreachable, or a `DeviceException` for other native device failures.
