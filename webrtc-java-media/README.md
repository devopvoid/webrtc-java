# webrtc-java-media

Media extension for [webrtc-java](https://github.com/devopvoid/webrtc-java). It reads media files
and network streams with FFmpeg and feeds them into a peer connection, so an application can send
a video file the way it would send a camera.

```java
MediaFileSource source = new MediaFileSource(Path.of("movie.mp4"));

VideoTrack videoTrack = factory.createVideoTrack("video", source.getVideoSource());
AudioTrack audioTrack = factory.createAudioTrack("audio", source.getAudioSource());

peerConnection.addTrack(videoTrack, List.of("stream"));
peerConnection.addTrack(audioTrack, List.of("stream"));

source.play();
```

## How it fits together

Decoding happens entirely in native code. The module does not carry frames through Java: it calls
into webrtc-java's native side through the small C interface in `webrtc_java_api.h`, which hands
decoded pictures to a `CustomVideoSource` and 10 ms chunks to a `CustomAudioSource` without a copy
per frame. Frames are paced in real time and carry their own presentation times, so playback keeps
the timing of the file rather than the timing of a thread.

A factory that sends media from this module sends pushed audio, so it cannot also send audio
captured by its `AudioDeviceModule`. Use a separate factory if you need both.

## FFmpeg

This module uses [FFmpeg](https://ffmpeg.org), licensed under the LGPL version 2.1 or later. The
FFmpeg source it is built from is the `third-party/ffmpeg` submodule, pinned to a release tag, and
is configured without `--enable-gpl` and without `--enable-nonfree`.

FFmpeg is linked dynamically and its libraries ship as separate files inside the platform jar, so
you may replace them with your own build, as the LGPL requires. The wrapper code in this module is
licensed under the Apache License 2.0 like the rest of webrtc-java.

## Building

The submodule has to be present:

```shell
git submodule update --init --depth 1 webrtc-java-media/third-party/ffmpeg
mvn install -Pwith-media-extension
```

The first build compiles FFmpeg, which takes a while; later builds reuse the install directory
(`ffmpeg.install.dir`, by default `~/ffmpeg/<platform>`).

Building FFmpeg needs `make` and `nasm`. On Windows they come from MSYS2, which the build looks for
in `C:/msys64` unless `MSYS2_ROOT` points somewhere else:

```shell
winget install MSYS2.MSYS2
C:\msys64\usr\bin\bash -lc "pacman -S --needed make nasm diffutils pkgconf"
```

Maven still runs from an ordinary shell. The build enters MSYS2 and the Visual Studio environment
on its own, because FFmpeg's configure needs a POSIX shell that can also see `cl` and `link`.
