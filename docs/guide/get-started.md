# Getting Started

This guide will help you get started with webrtc-java quickly. We'll cover installation and a first call with the client API. The [Core API](/guide/core-api) page makes the same call with the core API.

## Supported Platforms
Maven Central artifacts contain native libraries that can be loaded on the following platforms:

|           | x64     | arm     | arm64   |
|-----------|---------|---------|---------|
| Linux     | ✔      | ✔      | ✔      |
| macOS     | ✔      | -       | ✔      |
| Windows   | ✔      | -       | ✔      |

::: info
**Linux runtime dependencies**

The Linux native library links the X11 client libraries and the GLib, GBM and libdrm libraries that libwebrtc's desktop capturers use. A minimal installation, for example a container image, needs them installed before the library can load. On Debian and Ubuntu:

```shell
apt-get install libx11-6 libxext6 libxfixes3 libxdamage1 libxtst6 libxrandr2 libxcomposite1 libglib2.0-0 libgbm1 libdrm2
```

Since 0.17.0, PulseAudio, udev and D-Bus are opened at runtime only when audio devices, cameras or the screen saver inhibition are used, so the library loads without `libpulse0`, `libudev1` and `libdbus-1-3`. Earlier versions link them and need those three packages as well.
:::

## Installation

### Maven

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>{{ VERSION }}</version>
</dependency>
```

::: info
**Native library classifiers**

The webrtc-java artifact will automatically add the dependency for the native library artifact matching the currently running operating system and architecture at runtime.

If you encounter issues loading the native library "webrtc-java", you can declare the platform-specific classifier explicitly. For example, for Windows 64-Bit:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>{{ VERSION }}</version>
    <classifier>windows-x86_64</classifier>
</dependency>
```

Complete list of available classifiers:
- windows-x86_64
- windows-aarch64
- macos-x86_64
- macos-aarch64
- linux-x86_64
- linux-aarch64
- linux-aarch32
:::

#### Using SNAPSHOT Versions

If you want to use the latest development version, you can use a SNAPSHOT release:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>{{ VERSION_SNAPSHOT }}</version>
</dependency>
```

To use SNAPSHOT versions, you need to add the following repository configuration to your `pom.xml`:

```xml
<repositories>
    <repository>
        <name>Central Portal Snapshots</name>
        <id>central-portal-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

### Gradle

Add the following to your `build.gradle`:

```groovy
implementation "dev.onvoid.webrtc:webrtc-java:{{ VERSION }}"
```

For specific platforms, add the appropriate classifier:

```groovy
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{ VERSION }}", classifier: "windows-x86_64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{ VERSION }}", classifier: "windows-aarch64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{ VERSION }}", classifier: "macos-x86_64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{ VERSION }}", classifier: "macos-aarch64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{ VERSION }}", classifier: "linux-x86_64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{ VERSION }}", classifier: "linux-aarch64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{ VERSION }}", classifier: "linux-aarch32"
```

### Where the Native Library Is Loaded From

The native library is looked for in this order:

1. In the directory named by the system property `webrtc.java.library.dir`, if the library is there. It is loaded in place, nothing is extracted. Use this when the library is installed with the application, for example by a jpackage installer or a distribution package.
2. In the native JAR on the class path. The library is extracted into the directory named by `webrtc.java.extract.dir`, or into the temporary directory (`java.io.tmpdir`) if that is not set, and loaded from there. Set `webrtc.java.extract.dir` when the temporary directory does not allow running code, as when `/tmp` is mounted with `noexec`.
3. Where `System.loadLibrary` looks: the libraries of the Java runtime, which is where a jlink image keeps those of its modules, then `java.library.path`.

```shell
java -Dwebrtc.java.library.dir=/opt/myapp/lib -jar myapp.jar
java -Dwebrtc.java.extract.dir=/var/lib/myapp/natives -jar myapp.jar
```

A library loaded from a directory keeps the file name it has in the native JAR, for example `libwebrtc-java-linux-x86_64.so`. The media module's library is found the same way; its FFmpeg libraries go next to it under their names in its native JAR (`libavcodec.so.62` and so on). If the library is found nowhere, the `UnsatisfiedLinkError` lists every place that was tried.

A [GraalVM native image](/guide/advanced/native-image) carries the library inside itself, or finds it next to the executable.

The Linux libraries are built against glibc. They do not load on a system with the musl C library, such as Alpine Linux; the error says so when it finds one.

## Your First Call

webrtc-java has two APIs. The [Client API](/guide/client-api) in `dev.onvoid.webrtc.client` negotiates by itself, keeps your code off WebRTC's threads, and closes everything it created with one call. The core API in `dev.onvoid.webrtc` mirrors WebRTC's own classes one to one, for full control. Start with the client API; the [Core API](/guide/core-api) page shows the same steps with the core API.

### Create the Runtime

A `WebRtc` runtime owns everything the client API creates. Closing it, here at the end of the try-with-resources block, closes it all:

```java
import dev.onvoid.webrtc.client.*;

try (WebRtc rtc = WebRtc.create()) {
    // Peers, tracks and channels go here.
}
```

`WebRtc.create()` captures the microphone and plays to the speakers. `WebRtc.builder().audio(AudioMode.HEADLESS).build()` needs no audio hardware at all, which suits servers and tests.

### Connect to a Peer

Two peers find each other through signaling: they exchange offers, answers and ICE candidates over a channel you provide, such as a WebSocket. The library has no transport of its own; you hand it a function that sends, and pass it what the other side sent:

```java
Peer peer = rtc.createPeer(PeerOptions.builder()
        .iceServer("stun:stun.l.google.com:19302")
        .polite(true)
        .signaling(message -> socket.send(message.toJson()))
        .build());

socket.onText(text -> peer.signal(SignalMessage.fromJson(text)));
```

The peer negotiates by itself whenever its tracks or channels change. When both sides send an offer at the same moment, the polite one gives way, so set `polite(true)` on exactly one of the two peers. The JSON messages are the ones browsers use, so the other side can be a web page.

### Send and Receive Media

```java
peer.addTrack(rtc.media().microphone());
peer.addTrack(rtc.media().camera());

peer.onTrack(track -> {
    System.out.println("Receiving " + track.kind());

    if (track.isVideo()) {
        track.asVideo().onFrame(frame -> render(frame));
    }
});

peer.onStateChange(state -> System.out.println("Call is " + state));
```

Handlers run on a thread of the peer's own, one at a time and in order. They may take their time and may call any method, so a slow handler never stalls WebRTC.

### Exchange Messages

```java
DataChannel chat = peer.createChannel("chat");

chat.onMessage(message -> System.out.println("Peer says: " + message.text()));
chat.send("Hello");
```

Messages sent before the channel opens are sent once it does.

### Hang Up

`peer.close()` ends the call; closing the runtime ends every call and releases the camera, the microphone and the native resources. Operations return `CompletableFuture`s, which a thread such as `main` may wait for:

```java
peer.whenConnected().get(10, TimeUnit.SECONDS);
// ...
peer.close();
```

The [Client API](/guide/client-api) guide covers everything else: pushing your own audio and video, manual negotiation for WHIP and WHEP, statistics and errors. The [`ClientApiExample`](https://github.com/devopvoid/webrtc-java/blob/main/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/ClientApiExample.java) runs a whole call between two peers in one process, without any hardware.

## Next Steps

Now that you have a basic understanding of webrtc-java, you can explore more advanced features:

- [Client API][client-api] - Everything the client API does, and how its threads work
- [Core API][core-api] - The same call with the core API, which leaves negotiation, threads and cleanup to you
- [Data Channels][data-channels] - Learn more about data channels
- [RTC Stats][rtc-stats] - Monitor connection quality
- [Desktop Capture][desktop-capture] - Share screens and windows
- [All Guides][overview] - Complete list of guides

For a complete API reference, check the [JavaDoc][javadoc].

[client-api]: /guide/client-api
[core-api]: /guide/core-api
[audio-devices]: /guide/audio/audio-devices
[data-channels]: /guide/data/data-channels
[rtc-stats]: /guide/monitoring/rtc-stats
[desktop-capture]: /guide/video/desktop-capture
[overview]: /guide/
[javadoc]: https://javadoc.io/doc/dev.onvoid.webrtc/webrtc-java/latest/index.html