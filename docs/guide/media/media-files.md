# Media Files

This guide explains how to send a media file over a peer connection instead of a camera and a microphone, using the `webrtc-java-media` module. It covers:

- Adding the media module to a project
- Sending a file with `MediaFileSource`
- Reading what a source contains with `MediaReader` and `MediaInfo`
- Controlling playback and following it with a listener
- Feeding your own media sources with `MediaPlayer`

Sending a file is a common need: a test pattern instead of a webcam, a pre-recorded briefing, a video that has to reach several participants. Without help, an application has to bring its own decoder and push I420 frames into a `CustomVideoSource` itself. The media module removes that work by decoding with [FFmpeg](https://ffmpeg.org) inside the library.

Decoding happens entirely in native code. Frames never travel through Java: the module hands decoded pictures straight to the native side of a `CustomVideoSource`, and 10 ms chunks to a `CustomAudioSource`. They are paced in real time and carry the presentation times of the file, so what a receiver gets keeps the timing of the media rather than the timing of a Java thread.

## Adding the Module

The module is part of the normal build, and it builds FFmpeg from a submodule pinned to a release tag, so the submodule has to be present:

```shell
git submodule update --init --depth 1 webrtc-java-media/third-party/ffmpeg
mvn install
```

Building FFmpeg needs `make` and `nasm`. On Windows they come from MSYS2:

```shell
winget install MSYS2.MSYS2
C:\msys64\usr\bin\bash -lc "pacman -S --needed make nasm diffutils pkgconf"
```

Maven still runs from an ordinary shell; the build enters MSYS2 and the Visual Studio environment on its own. The first build compiles FFmpeg, which takes a while; later builds reuse the install directory.

Once installed, depend on it alongside `webrtc-java`. It takes two entries: one for the Java API, and one for the natives of the platform you are running on.

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java-media</artifactId>
    <version>0.19.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java-media</artifactId>
    <version>0.19.0-SNAPSHOT</version>
    <classifier>windows-x86_64</classifier>
</dependency>
```

The classifier jar carries the module's native library together with the FFmpeg shared libraries it uses, so applications that do not use this module never download FFmpeg. Replace the classifier with the platform you are building for: `windows-x86_64`, `windows-aarch64`, `linux-x86_64`, `linux-aarch64`, `linux-aarch32`, `macos-x86_64` or `macos-aarch64`.

::: info
Unlike `webrtc-java`, which brings its natives along by itself, this module cannot: the natives are built by the module rather than by a separate one, so a dependency on them would have nothing to resolve against on a first build. Asking for them explicitly is the price of that.
:::

## Sending a File

`MediaFileSource` is the short way to do all of it. It opens the source, creates a media source for each kind of media the file actually has, and wires a player to feed them:

```java
// Import required classes
import dev.onvoid.webrtc.media.ffmpeg.MediaFileSource;
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

A file with no audio has no audio source, and likewise for video, so check before making a track:

```java
if (source.getVideoSource() != null) {
    VideoTrack videoTrack = factory.createVideoTrack("video", source.getVideoSource());
    peerConnection.addTrack(videoTrack, List.of("stream"));
}
```

::: warning A factory sends either pushed audio or captured audio
A `PeerConnectionFactory` fed from this module is sending pushed audio and cannot also send audio captured by its `AudioDeviceModule`. Give such a factory a dummy audio layer, and use a second factory if an application needs both:

```java
AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);
```
:::

## Reading What a Source Contains

`MediaReader` opens a source and reports what is in it, without playing anything:

```java
// Import required classes
import dev.onvoid.webrtc.media.ffmpeg.MediaInfo;
import dev.onvoid.webrtc.media.ffmpeg.MediaReader;

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

A `MediaFileSource` already has this, so there is no need to open the file twice:

```java
MediaInfo info = source.getInfo();
```

::: info
Duration is `0` for a source whose container does not say how long it runs, which is the case for live streams. Opening fails if a source holds nothing that can be played.
:::

## Controlling Playback

```java
source.play();                  // start, or resume after a pause
source.pause();                 // hold playback where it is
source.seek(30_000_000);        // move to 30 seconds
source.setLooping(true);        // start over instead of ending
source.getPositionUs();         // where playback has got to
source.getState();              // IDLE, PLAYING, PAUSED, ENDED or CLOSED
```

Looping keeps the timing across the seam, so a looping file works as a stand-in for a camera that never stops. A looping source never reports an end of stream.

A seek lands on the keyframe at or before the position asked for, which is how far back the decoder has to go to produce a picture at all. How close that is to the position asked for depends on how often the media was encoded with keyframes.

## Following Playback

```java
// Import required classes
import dev.onvoid.webrtc.media.ffmpeg.MediaPlayerListener;
import dev.onvoid.webrtc.media.ffmpeg.MediaPlayerState;

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
        System.out.println("Playback failed: " + message);
    }
});
```

::: warning
Every call arrives on the player's own thread, and that thread is the one decoding the media. A listener must return promptly, and must not wait on the player.
:::

## Feeding Your Own Media Sources

`MediaFileSource` creates the media sources for you. An application that needs its own — to feed a source it already created, or to send only the video of a file — uses `MediaPlayer` directly:

```java
// Import required classes
import dev.onvoid.webrtc.media.ffmpeg.MediaPlayer;
import dev.onvoid.webrtc.media.ffmpeg.MediaReader;
import dev.onvoid.webrtc.media.video.CustomVideoSource;

CustomVideoSource videoSource = new CustomVideoSource();

// Video only: passing null for the audio source decodes and drops the audio.
MediaPlayer player = new MediaPlayer(new MediaReader(path), videoSource, null);

VideoTrack videoTrack = factory.createVideoTrack("video", videoSource);

player.play();
```

::: info
The player takes over the reader it is given. That reader must not be used or closed afterwards; closing the player releases it.
:::

## Closing

Closing a `MediaFileSource` stops playback and releases the player along with both media sources. Release the senders a peer connection handed out, and the tracks, before that:

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
| **Video** | H.264, H.265/HEVC, VP8, VP9, MPEG-4 (including Xvid and DivX), Microsoft MPEG-4 v1 to v3, MJPEG |
| **Audio** | AAC, MP3, MP2, AC-3, Opus, Vorbis, FLAC, PCM, MS and IMA ADPCM |

Audio of any rate or layout is resampled to what WebRTC takes, which is 48 kHz 16-bit PCM in mono or stereo. Video that decodes to I420 — almost all 8-bit H.264, VP8, VP9 and MPEG-4 — reaches the encoder without being copied; anything else is converted first.

Only local files play today. FFmpeg demuxes network sources just as well, so the same code will cover http, rtsp and rtmp once those protocols are turned on in the build.

## Licensing

The module uses FFmpeg under the LGPL version 2.1 or later. It is configured without `--enable-gpl` and without `--enable-nonfree`, and FFmpeg is linked dynamically and shipped as separate files inside the platform jar, so its libraries may be replaced with your own build, as the LGPL requires. The wrapper code is licensed under the Apache License 2.0 like the rest of webrtc-java.

Each platform jar carries the LGPL text and a notice under `META-INF/licenses/ffmpeg`, naming the FFmpeg release the libraries are built from, unmodified, and where its source is. If you redistribute your application with these jars, keep those files with them.

Some of the formats FFmpeg decodes, such as H.264, H.265/HEVC and AAC, may be covered by patents in some countries. Whether your use of them needs a patent license is for you to determine.

## Complete Example

See `MediaFileExample` in the `webrtc-examples` module, which opens a file, reports what it contains, creates tracks, adds them to a peer connection and follows playback to the end.

```shell
mvn -pl webrtc-examples compile
```
