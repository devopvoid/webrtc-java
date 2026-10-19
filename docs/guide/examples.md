# Examples

This section provides an overview of the example applications included in the `webrtc-examples` Maven module. These examples demonstrate various features and capabilities of the webrtc-java library.

## Client API

The [`ClientApiExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/ClientApiExample.java) connects two peers in one process with the [Client API](/guide/client-api), and sends video, audio and chat messages from one to the other. It needs no audio or video hardware and no server.

**Key features demonstrated:**
- Creating a headless `WebRtc` runtime, which needs no microphone or speakers
- Connecting two peers through a signaling that carries JSON text, as a WebSocket would
- Letting perfect negotiation run the offer and answer exchange, with one polite peer
- Pushing video frames, and audio in pieces of any length
- Receiving remote tracks, their frames and their audio
- Exchanging messages on a data channel
- Reading the connection's statistics once a second
- Closing the peers, tracks and factory by closing the runtime

This example is the shortest way to see a complete call working, and a starting point for applications built on the client API.

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.ClientApiExample" -D"exec.args=10"
```

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

The [`WhepExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/WhepExample.java) implements a client of the WebRTC HTTP Egress Protocol (WHEP), which receives a stream from a server, with the [Client API](/guide/client-api).

**Key features demonstrated:**
- Creating a receive-only peer that negotiates by hand
- Creating an offer that already holds the local candidates, as WHEP servers expect
- Sending the offer to a WHEP endpoint and applying the answer it returns
- Receiving the stream's video and audio
- Ending the session with an HTTP `DELETE` of the resource the server created

This example is useful for applications that need to receive media streams from WHEP-compatible servers, such as live streaming platforms. The endpoint and the number of seconds to receive are optional:

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.WhepExample" -D"exec.args=http://localhost:8889/mystream/whep 10"
```

## Codec List

The [`CodecListExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/CodecListExample.java) demonstrates how to list all supported codecs with the WebRTC peer-connection-factory.

**Key features demonstrated:**
- Creating a `PeerConnectionFactory` with the `HardwareVideoEncoderFactory` and `HardwareVideoDecoderFactory`
- Getting the supported codecs for both sending and receiving audio and video
- Displaying detailed information about each codec
- Listing the video codecs of the hardware factories and marking those the GPU encodes or decodes, with `getHardwareCodecs()`

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

## Encrypted Recording

The [`EncryptedRecordingExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/EncryptedRecordingExample.java) encrypts a call end to end with encoded frame transforms, and records what the receiving side decrypted into a media file. See the [Encoded Transforms](/guide/advanced/encoded-transforms) and [Media Recording](/guide/media/media-recording) guides for the APIs it uses.

**Key features demonstrated:**
- Encrypting every encoded audio and video frame with AES-GCM in an `RTCEncodedFrameTransformer` on the senders, and decrypting it on the receivers
- Keeping the codec header of each frame in the clear for the packetizer, and authenticating it instead
- Dropping frames that fail to encrypt or to authenticate, rather than passing them on
- Recording the receivers with a `MediaRecorder`, without decoding or re-encoding

::: info
Like the [Media File](#media-file) example, this one needs the `webrtc-java-media` module. The output file is optional; its extension picks the container.

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.EncryptedRecordingExample" -D"exec.args=call.mkv"
```
:::

## Web Client

The [`WebClientExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/web/WebClientExample.java) calls a browser from Java over WebSocket signaling, with the [Client API](/guide/client-api). The browser client is served by the example's `WebServer`.

**Key features demonstrated:**
- Connecting to a signaling server using WebSockets
- Implementing a `Signaling` over the application's own message format, which also names the sender and receiver of each message
- Sending generated audio and video, pushed into tracks of the runtime
- Letting the peer negotiate by itself, and starting a new peer whenever the browser joins the room
- Joining and leaving rooms
- Logging the audio and video the browser sends

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