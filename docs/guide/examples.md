# Examples

This section provides an overview of the example applications included in the `webrtc-examples` Maven module. These examples demonstrate various features and capabilities of the webrtc-java library.

## PeerConnection

The [`PeerConnectionExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/PeerConnectionExample.java) demonstrates how to set up a peer connection with audio and video tracks to be able to send and receive media.

**Key features demonstrated:**
- Creating a `PeerConnectionFactory`
- Creating audio and video tracks
- Setting up a peer connection
- Adding tracks to the peer connection for sending media
- Implementing callbacks to receive incoming audio and video frames

This example provides a foundation for building WebRTC applications that need to handle audio and video communication.

## WHEP

The [`WhepExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/WhepExample.java) demonstrates an implementation of WebRTC HTTP Egress Protocol (WHEP) client, which is a standardized protocol for WebRTC ingestion.

**Key features demonstrated:**
- Setting up a WebRTC peer connection
- Creating and sending an SDP offer to a WHEP endpoint
- Receiving and processing an SDP answer
- Establishing media streaming over WebRTC

This example is useful for applications that need to receive media streams from WHEP-compatible servers, such as live streaming platforms.

## Codec List

The [`CodecListExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/CodecListExample.java) demonstrates how to list all supported codecs with the WebRTC peer-connection-factory.

**Key features demonstrated:**
- Creating a `PeerConnectionFactory`
- Getting the supported codecs for both sending and receiving audio and video
- Displaying detailed information about each codec

This example is useful for understanding what codecs are available on the current system, which can help with debugging compatibility issues or optimizing media quality.

## Desktop Video

The [`DesktopVideoExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/DesktopVideoExample.java) demonstrates how to set up a peer connection with a desktop video source for screen or window capture.

**Key features demonstrated:**
- Creating a `PeerConnectionFactory`
- Getting available desktop sources (screens and windows)
- Creating a `VideoDesktopSource` for capturing screen or window content
- Configuring the `VideoDesktopSource` properties
- Creating a video track with the desktop source
- Setting up a peer connection

This example is particularly useful for applications that need to implement screen sharing or remote desktop functionality.

## Media File

The [`MediaFileExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/MediaFileExample.java) demonstrates how to send a media file over a peer connection, in place of a camera and a microphone. See the [Media Files](/guide/media/media-files) guide for the API it uses.

**Key features demonstrated:**
- Opening a media file, or a live RTSP stream, with a `MediaFileSource`
- Reading what the source contains from its `MediaInfo`
- Creating audio and video tracks from the media sources it feeds
- Adding those tracks to a peer connection
- Following playback through a `MediaPlayerListener`

This example is useful for applications that stream pre-recorded media, relay an IP camera into WebRTC, or need a dependable stand-in for a camera in testing.

::: info
This example needs the `webrtc-java-media` module, which builds FFmpeg from the `third-party/ffmpeg` submodule. Make sure the submodule is checked out before building, as the [Media Files](/guide/media/media-files) guide describes.

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.MediaFileExample" -D"exec.args=movie.mp4"
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.MediaFileExample" -D"exec.args=rtsp://camera.local/stream1"
```
:::

## Media File Player

The [`MediaFilePlayerExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/MediaFilePlayerExample.java) is a Swing application that sends a media file, or a live RTSP stream, from one peer connection to another in the same process, then shows the received video and plays the received audio in sync. Start and Stop buttons set up and tear down the whole session.

**Key features demonstrated:**
- Connecting two peer connections in one application, without a signaling server
- Using one factory with a dummy audio layer to send the file, and another with the platform audio layer to play what is received
- Keeping audio and video in sync by sending both tracks in the same media stream
- Raising the video sender's `maxBitrate` above WebRTC's default, so a high-resolution file is not held to a fraction of its size
- Rendering received video frames in a Swing component with `VideoBufferConverter`
- Showing the source's format, the playback position, and live receive metrics (codec, resolution, frame rate, bitrate, packet loss, jitter, audio level) read from `getStats()`
- Releasing senders, peer connections, tracks and the source in order on Stop

::: info
Like the [Media File](#media-file) example, this one needs the `webrtc-java-media` module. The source argument is optional; a file can also be chosen from the window, or a stream URL typed into it. A stream URL's credentials are never shown, and the Loop box is disabled while a live stream plays, since it has no length to start over from.

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.MediaFilePlayerExample" -D"exec.args=movie.mp4"
```
:::

## Web Client

The [`WebClientExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/web/WebClientExample.java) demonstrates how to combine WebSocket signaling with WebRTC peer connections for real-time communication between web and Java clients.

**Key features demonstrated:**
- Connecting to a signaling server using WebSockets
- Setting up audio and video media tracks
- Creating and managing WebRTC peer connections
- Handling signaling messages (offers, answers, ICE candidates)
- Joining and leaving rooms for multi-user communication
- Using custom audio sources for audio streaming

This example is valuable for applications that need to establish WebRTC connections between Java clients and web browsers, enabling cross-platform real-time communication with audio and video.

::: info
Before running the `WebClientExample`, you need to start the `WebServer` first. The `WebServer` provides the signaling service required for WebRTC connection establishment:
:::

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.web.server.WebServer"
```

And the browser client is reachable at this url: `https://localhost:8443`


## Running the Examples

To run these examples, you need to navigate into the directory `webrtc-java/webrtc-example` in the project.
You can then execute the main method of each example class.

For example, to run the `CodecListExample`:

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.CodecListExample"
```

Or to run the `WebClientExample`:

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.web.WebClientExample"
```

Note that these examples focus on setting up the local components for WebRTC communication. In a real application, you would need to establish a connection with a remote peer through a signaling channel (e.g., WebSocket). The `WebClientExample` demonstrates this by connecting to a signaling server.