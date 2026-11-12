# Media Files

<ApiNote client="/guide/client/sending-media#sending-a-media-file" title="Sending a Media File" />

The `webrtc-java-media` module sends a media file over a peer connection, in place of a camera and a microphone. It also plays live streams from IP cameras and media servers over RTSP.

Sending a file is a common need: a test pattern instead of a webcam, a pre-recorded briefing, or a video that has to reach several participants. Without the module, an application has to bring its own decoder and push I420 frames into a `CustomVideoSource` itself. The module decodes with [FFmpeg](https://ffmpeg.org) inside the library instead.

## Adding the Module

The module is part of the normal build. It builds FFmpeg and dav1d, the software decoder of AV1, from submodules pinned to release tags, so the submodules have to be present:

```shell
git submodule update --init --depth 1 webrtc-media/third-party/ffmpeg webrtc-media/third-party/dav1d
mvn install
```

Building FFmpeg needs `make` and `nasm`. Building dav1d needs [Meson](https://mesonbuild.com) (`pip install meson`) and Ninja. On Windows, `make` and `nasm` come from MSYS2:

```shell
winget install MSYS2.MSYS2
C:\msys64\usr\bin\bash -lc "pacman -S --needed make nasm diffutils pkgconf"
```

Maven still runs from an ordinary shell: the build enters MSYS2 and the Visual Studio environment by itself. The first build compiles FFmpeg and dav1d, which takes a while. Later builds reuse the install directory.

Once installed, depend on the module beside `webrtc-java`. It takes two entries: one for the Java API, and one for the natives of the platform you run on:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java-media</artifactId>
    <version>{{ VERSION }}</version>
</dependency>
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java-media</artifactId>
    <version>{{ VERSION }}</version>
    <classifier>windows-x86_64</classifier>
</dependency>
```

The classifier jar holds the module's native library and the FFmpeg shared libraries it uses. Applications that do not use the module therefore never download FFmpeg. Use the classifier of the platform you build for: `windows-x86_64`, `windows-aarch64`, `linux-x86_64`, `linux-aarch64`, `linux-aarch32`, `macos-x86_64` or `macos-aarch64`.

::: info
Unlike `webrtc-java`, this module cannot bring its natives along by itself. The module builds its natives itself, not a separate module, so on a first build a dependency on them would have nothing to resolve against. That is why you ask for them explicitly.
:::

## Sending a File

`MediaFileSource` does all of it in one step. It opens the source, creates a media source for each kind of media the file has, and connects a player that feeds them:

```java
// Import required classes
import dev.onvoid.webrtc.media.player.MediaFileSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;
import java.nio.file.Path;
import java.util.List;

MediaFileSource source = new MediaFileSource(Path.of("movie.mp4"));

// Create tracks from the media sources the file feeds.
VideoTrack videoTrack = factory.createVideoTrack("video", source.getVideoSource());
AudioTrack audioTrack = factory.createAudioTrack("audio", source.getAudioSource());

peerConnection.addTrack(videoTrack, List.of("stream"));
peerConnection.addTrack(audioTrack, List.of("stream"));

// Start sending.
source.play();
```

A file without audio has no audio source, and a file without video has no video source. Check before you create a track:

```java
if (source.getVideoSource() != null) {
    VideoTrack videoTrack = factory.createVideoTrack("video", source.getVideoSource());
    peerConnection.addTrack(videoTrack, List.of("stream"));
}
```

The module pushes its audio into the track, as a `CustomAudioSource` does. The same factory and peer connection can therefore send the microphone beside it. A factory that sends only pushed audio never opens the recording device.

Decoding happens entirely in native code, and frames never pass through Java. The module hands decoded pictures straight to the native side of a `CustomVideoSource`, and audio in 10 ms chunks to a `CustomAudioSource`.

Both are paced in real time and carry the presentation times of the file. A receiver therefore gets the timing of the media, not the timing of a Java thread.

## Reading What a Source Contains

`MediaReader` opens a source and reports what is in it, without playing anything:

```java
// Import required classes
import dev.onvoid.webrtc.media.player.MediaInfo;
import dev.onvoid.webrtc.media.player.MediaReader;

try (MediaReader reader = new MediaReader(Path.of("movie.mp4"))) {
    MediaInfo info = reader.getInfo();

    System.out.println("Runs " + info.getDurationUs() / 1_000_000.0 + " s");

    if (info.hasVideo()) {
        System.out.println(info.getVideoWidth() + "x" + info.getVideoHeight()
                + " at " + info.getFrameRate() + " fps, " + info.getVideoCodec());
    }
    if (info.hasAudio()) {
        System.out.println(info.getSampleRate() + " Hz, "
                + info.getChannels() + " channels, " + info.getAudioCodec());
    }
}
```

The width and height are those of the video as it is shown. They can differ from what the file stores:

- **Rotation:** A phone stores a portrait video sideways, with a rotation beside it. Its frames keep the stored size and carry the rotation in `VideoFrame.rotation`, which the video views and the receiving peers apply. `MediaInfo` reports the upright size.
- **Pixels that are not square,** such as those of 720x576 PAL with 16:15 pixels, are scaled to square ones while the video plays, here to 768x576. WebRTC has no way to describe them. One side grows, so that no detail is lost, at the price of one copy of each picture.
- **Exceptions:** Only quarter turns are applied. A video that is to be shown mirrored, or turned by another angle, plays as it is stored. So does a video whose pixels are said to be more than four times as wide as high, or the other way round.

A `MediaFileSource` already has this information, so there is no need to open the file twice:

```java
MediaInfo info = source.getInfo();
```

::: info
The duration is `0` for a source whose container does not say how long it runs, as is the case for live streams. Opening fails if a source holds nothing that can be played.
:::

## Controlling Playback

```java
source.play();                  // start, or resume after a pause
source.pause();                 // hold playback where it is
source.seek(30_000_000);        // move to 30 seconds
source.fastSeek(30_000_000);    // move to the keyframe at or before 30 seconds
source.setLooping(true);        // start over instead of ending
source.getPositionUs();         // where playback has got to
source.getState();              // IDLE, PLAYING, PAUSED, ENDED or CLOSED
```

Looping keeps the timing across the seam, so a looping file can stand in for a camera that never stops. A looping source never reports an end of stream.

Positions count from the start of the source, which is its first video or audio frame, whichever comes first. The times a container gives its frames need not begin at zero.

A file cut out of a longer recording, or an MPEG-TS capture, may start at any time, and `seek(2_000_000)` still means two seconds into it. Video and audio share that start, so a stream that begins after the other one keeps that distance.

### Seeking

`seek` starts playback at the position asked for. To produce a picture at all, the decoder has to go back to the keyframe at or before the position. The frames from there up to the position are decoded and dropped, and the audio is cut at the position.

How long a seek takes therefore depends on how often the media was encoded with keyframes, not on how long the media is:

- MKV and MP4 have an index that takes the reader straight to that keyframe.
- MPEG-TS has none. A search for the position by its timestamps can land past the keyframe before it. The player then searches again further back: one second before the position, then two, four and so on, so that the seek still starts at the position.

`fastSeek` moves to that keyframe and plays from there, without decoding up to the position. It takes no longer, however far apart the keyframes are.

That suits a slider being dragged, as the `fastSeek()` of browsers does: fast seeks while it moves, and a `seek` once it is let go. In a container without an index, such as MPEG-TS, the keyframe can be the one after the position.

## Following Playback

```java
// Import required classes
import dev.onvoid.webrtc.media.player.MediaPlayerListener;
import dev.onvoid.webrtc.media.player.MediaPlayerState;

source.setListener(new MediaPlayerListener() {

    @Override
    public void onStateChanged(MediaPlayerState state) {
        System.out.println("Player state: " + state);
    }

    @Override
    public void onEndOfStream() {
        System.out.println("The source ran out.");
    }

    @Override
    public void onError(String message) {
        System.out.println("Playback error: " + message);
    }
});
```

Output failures are reported through `onError`:

- A single rejected video frame or audio chunk is dropped, without advancing the position for it. Playback continues.
- These reports are limited to one per second for video and one per second for audio.
- Ten failed deliveries in a row, of either kind, stop playback in `PAUSED` and produce a final error report.
- An unexpected C++ exception on the decoder thread also stops playback in `PAUSED`.

After either of these stops, `play()` and `seek()` have no effect. To retry, close the player and create a new one.

::: warning
- Every call arrives on the player's own event thread. It never arrives on the thread that called `play()`, `pause()` or `close()`, nor on the one decoding the media.
- The calls come one at a time, in the order the player went through what they report. A listener therefore needs no locking against itself.
- A command returns without waiting for its state change to be reported. `getState()` can therefore already read a later state than the call in progress reports.
- `close()` returns once the listener has been told of everything, `CLOSED` last.
- A listener must return promptly: the calls behind it wait, and so does a `close()` from another thread. It must not wait on the player.
- Closing the player, or the `MediaFileSource`, from a listener is fine. It takes effect at once, and the native player is released on another thread once the listener has returned.
- The same holds for a sink of a track made from the player's sources. The player calls sinks on threads of its own as well.
:::

## Memory

Decoding runs ahead of playback by up to sixty pictures, and by no more than what `MediaPlayer.getMaxQueuedVideoBytes()` reports: 256 MiB. That holds sixty pictures of 1080p, or about twenty of 4K. Whatever the resolution, a player takes no more than that for pictures waiting to be delivered.

Several players take that much each, on top of what the decoders use themselves. To see what an application really needs, ask the players that run:

```java
source.getQueuedVideoBytes();       // what the queued pictures take right now
source.getPeakQueuedVideoBytes();   // the most they have taken so far
```

## Feeding Your Own Media Sources

`MediaFileSource` creates the media sources for you. To feed a source you already created, or to send only the video of a file, use `MediaPlayer` directly:

```java
// Import required classes
import dev.onvoid.webrtc.media.player.MediaPlayer;
import dev.onvoid.webrtc.media.player.MediaReader;
import dev.onvoid.webrtc.media.video.CustomVideoSource;

CustomVideoSource videoSource = new CustomVideoSource();

// Video only: passing null for the audio source decodes and drops the audio.
MediaPlayer player = new MediaPlayer(new MediaReader(path), videoSource, null);

VideoTrack videoTrack = factory.createVideoTrack("video", videoSource);

player.play();
```

::: info
- The player takes over the reader it is given. Don't use or close that reader afterwards; closing the player releases it.
- The player keeps the native side of the media sources it feeds alive until it is closed. Disposing of a source or its track while the player runs is therefore safe.
- A source that was already disposed is rejected with `IllegalStateException`, before the reader is taken over. The caller can then still use or close that reader.
- A source disposed while the constructor runs is safe too: the constructor either acquires its own reference to it or rejects it.
:::

## Decoding in Hardware

At high resolutions, and for every stream of a camera wall, decoding H.264, H.265/HEVC, VP9 and AV1 takes a good part of a processor. A player can decode them on the media engine or GPU of the machine instead. It does so when asked to, with a flag on `MediaFileSource` or `MediaPlayer`:

```java
// A file whose video is decoded in hardware, where the platform can.
MediaFileSource source = new MediaFileSource(path, true);

// The same for a player of your own.
MediaPlayer player = new MediaPlayer(new MediaReader(path), videoSource, null, true);

// What is actually in use: false if the platform or the stream has no
// hardware decoder for it.
boolean hardware = source.getPlayer().isHardwareDecoding();
```

Hardware decoding is off unless asked for, except for H.265/HEVC. The pictures are the same ones software decoding gives. The tests compare every frame of media in each of these codecs with its software counterpart; for HEVC, with checksums of the pictures that FFmpeg's software decoder gave once.

::: warning HEVC is decoded in hardware only
A player decodes HEVC on the media engine or GPU whether or not the flag is set, and never in software. `isHardwareDecoding()` is always `true` for it.

- If the machine has no hardware decoder for HEVC, creating the player fails with an `IOException`.
- If the hardware does not take a stream, such as a profile it cannot decode, or fails while playing, playback stops with an error.
- The [fallback to software](#fallback-to-software) does not apply to HEVC.
:::

### Platforms

| Platform | Decodes through | Needs |
|---|---|---|
| macOS | VideoToolbox | |
| Windows | Direct3D 12 | Windows 10 version 2004 or later, with a driver that decodes video through Direct3D 12 |
| Windows | Direct3D 11 | Used where Direct3D 12 is not available |
| Linux, NVIDIA GPUs | NVDEC | `libcuda.so.1` and `libnvcuvid.so.1`, which the driver provides |
| Linux, Intel and AMD GPUs | VA-API | `libva.so.2` and `libva-drm.so.2` with a driver: Intel's `iHD` from intel-media-driver, or Mesa's `radeonsi` |
| Linux, Intel and AMD GPUs | Vulkan Video, otherwise | The Vulkan loader `libvulkan.so.1` and a driver with the video extensions of the codec: Mesa's ANV or RADV |

- Linux means x86-64 and ARM64.
- For Vulkan Video, Mesa's drivers must be recent enough for each codec, and AV1 and VP9 need the newest. Some distributions ship Mesa with its video extensions switched off.
- VA-API comes before Vulkan Video, because a Vulkan device can be created even where its driver does not decode, and the player picks a device by whether it can be created.
- These libraries are loaded only when a player asks for hardware, so a machine without them is not affected.
- The flag is accepted everywhere. Where the platform has no hardware decoder, or the FFmpeg build has none for it (32-bit ARM), the video is decoded in software, except HEVC. `isHardwareDecoding()` then says so.

### Codecs

H.264, H.265/HEVC, VP9 and AV1 are decoded in hardware where the GPU decodes them. A GPU that is a few years old may lack AV1, and on a Mac only an M3 or later has it.

VP8, MPEG-4 and MJPEG are decoded in software. So is AV1 where there is no GPU decoder for it. It is then decoded by dav1d, which is built into the FFmpeg libraries.

### Fallback to Software

For every codec but HEVC:

- A stream the hardware does not take, such as a profile it cannot decode, is decoded in software. The only sign of it is `isHardwareDecoding()` turning `false`.
- If the hardware fails before its first picture, the packets it was sent are decoded again in software, and none is lost.
- If the hardware fails after its first picture, decoding goes on in software from the next key frame.
- After the first picture, a damaged packet is not taken for a hardware failure. As with software decoding, playback stops with an error, and the hardware keeps the stream.

### What It Saves

Hardware decoding saves processor time, not the copy. A decoded picture is read back from the media engine into system memory, and converted to I420 as WebRTC wants it.

::: details Measurements
Processor time per frame:

| Machine | Stream | Software | Hardware |
|---|---|---|---|
| Apple M2 | 1080p H.264 | 11.6 ms | 1.4 ms |
| Apple M2 | 4K H.264 | 35.7 ms | 4.2 ms |
| NVIDIA GeForce RTX 3080, Direct3D 12 | 1080p H.264 | 3.6 ms | about 2.1 ms |
| NVIDIA GeForce RTX 3080, Direct3D 12 | 4K H.264 | 6.6 ms | 5.4 ms |

- On the M2, hardware takes about seven to eight times less. Windows gains less.
- Per frame, software on all cores is faster on the clock. At playback speed, that does not matter.
- Direct3D 11 reads the picture back more slowly and took more than software at 4K (12.4 ms). That is why Direct3D 12 comes first.
- These are figures for one machine each and for synthetic media, not a promise. Try it on your own streams before relying on it.
:::

## Playing a Live Stream

A source can also be a live stream behind an `rtsp://` URL, which is what IP cameras, video recorders and most media servers offer. Everything above works the same way; only the URL differs:

```java
MediaFileSource source = new MediaFileSource("rtsp://camera.local:554/stream1");
```

Credentials go into the URL, as in `rtsp://user:password@camera.local/stream1`. FFmpeg first asks the server for RTP over UDP. If the server refuses UDP, or no UDP packets arrive, it falls back to RTP interleaved on the RTSP connection over TCP, which gets a stream through most firewalls.

A network source can stall or disappear, so every operation that waits on a source has a time limit: opening it, and then each read during playback. The limit is 10 seconds unless you pass another:

```java
// Import required classes
import java.time.Duration;

MediaFileSource source = new MediaFileSource("rtsp://camera.local/stream1", Duration.ofSeconds(5));
```

- `Duration.ZERO` waits as long as it takes. It is the only limit that does.
- A positive limit waits at least a microsecond. One too long for microseconds to count waits as long as they can count.
- A negative limit is rejected.

If the server cannot be reached, the constructor throws an `IOException` once the time is up. A stream that stops sending during playback is reported to the listener's `onError`, and the player is left `PAUSED`. Closing a player never waits for the time limit: it breaks off whatever the player is waiting for at once.

A live stream has no end and no length:

- Its `MediaInfo` reports a duration of 0.
- Looping does not apply, and seeking is reported as an error.
- Pausing stops reading from the server, but the server keeps sending. Resuming carries on from where playback paused, which leaves it behind real time by as long as it was paused.
- To get back to live, close the source and open it again.

## Closing

Closing a `MediaFileSource` stops playback, and releases the player along with both media sources. Before that, release the senders a peer connection handed out, and the tracks:

```java
videoSender.dispose();
audioSender.dispose();
peerConnection.close();

source.close();
```

## What Can Be Played

The FFmpeg build is deliberately small, and carries only what this module plays:

| | |
| --- | --- |
| **Containers** | MP4 and MOV, Matroska and WebM, AVI, MPEG-TS, FLV, WAV, MP3, Ogg, FLAC, AAC |
| **Video** | H.264, H.265/HEVC (hardware only), VP8, VP9, AV1, MPEG-4 (including Xvid and DivX), Microsoft MPEG-4 v1 to v3, MJPEG |
| **Audio** | AAC, MP3, MP2, AC-3, Opus, Vorbis, FLAC, PCM, MS and IMA ADPCM |

Audio of any rate or layout is resampled to what WebRTC takes: 48 kHz 16-bit PCM, in mono or stereo. Video that decodes to I420, which is almost all 8-bit H.264, VP8, VP9 and MPEG-4, reaches the encoder without being copied. Anything else is converted first.

Video keeps its color matrix, full or limited range, color primaries and transfer function through conversion and into the native WebRTC frame, also after hardware readback:

- Without matrix and range metadata, BT.601 limited range is assumed.
- RGB and JPEG pixel formats imply full range.
- RGB is converted to BT.601 YUV.

The negotiated encoder and the receiver decide how this information is carried and displayed.

Sources can be local files, or live streams over RTSP (`rtsp://`), with RTP over UDP or TCP. Protocols that need TLS, such as `rtsps://` and `https://`, are not part of the build, and neither are HLS, DASH and RTMP.

A source cannot reach any other protocol either, not even one that FFmpeg uses internally. A URL passed on from a user therefore cannot make the module fetch something else.

## Licensing

The module uses FFmpeg under the LGPL version 2.1 or later. FFmpeg is configured without `--enable-gpl` and without `--enable-nonfree`. It is linked dynamically and shipped as separate files inside the platform jar, so its libraries may be replaced with your own build, as the LGPL requires.

The wrapper code is licensed under the Apache License 2.0, like the rest of webrtc-java.

Each platform jar carries the LGPL text and a notice under `META-INF/licenses/ffmpeg`. The notice names the FFmpeg release the libraries are built from, unmodified, and where its source is. If you redistribute your application with these jars, keep those files with them.

Some of the formats FFmpeg decodes, such as H.264, H.265/HEVC and AAC, may be covered by patents in some countries. Whether your use of them needs a patent license is for you to determine.

## Complete Example

See `MediaFileExample` in the `webrtc-examples` module. It opens a file, reports what it contains, sends its tracks from one [Client API](/guide/client-api) peer to another, and follows playback to the end.

```shell
mvn -pl webrtc-examples compile
```
