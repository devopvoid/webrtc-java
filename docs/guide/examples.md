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

The [`PeerConnectionExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/PeerConnectionExample.java) sends the camera and the microphone from one peer to another in the same process, with the [Client API](/guide/client-api), and logs the video frames and audio that arrive.

**Key features demonstrated:**
- Creating a `WebRtc` runtime that captures from and plays to the audio devices
- Listing the cameras and microphones
- Opening the camera and the microphone as local tracks
- Connecting two peers through a loopback signaling, in place of a server
- Receiving the remote tracks, their frames and their audio
- Muting a received audio track, while its audio still reaches a handler

This example provides a foundation for building WebRTC applications that need to handle audio and video communication. Press Enter to end the call:

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.PeerConnectionExample"
```

## WHEP

The [`WhepExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/WhepExample.java) implements a client of the WebRTC HTTP Egress Protocol (WHEP), which receives a stream from a server, with the [Client API](/guide/client-api).

**Key features demonstrated:**
- Receiving a stream from a WHEP endpoint with `Whep`
- Logging the stream's video frames and audio
- Ending the session, which deletes it on the server

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

The [`DesktopVideoExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/DesktopVideoExample.java) shares a screen or a window from one peer to another in the same process, with the [Client API](/guide/client-api).

**Key features demonstrated:**
- Listing the screens and windows that can be captured
- Capturing one of them as a local video track, with a frame rate and a largest size
- Connecting two peers through a loopback signaling, in place of a server
- Reading the resolution and frame rate the sender encodes

This example is particularly useful for applications that need to implement screen sharing or remote desktop functionality. It shares the first screen, or the first window with `window`. Under Wayland, the system asks which one to share instead.

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.DesktopVideoExample"
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.DesktopVideoExample" -D"exec.args=window"
```

## Media File

The [`MediaFileExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/MediaFileExample.java) demonstrates how to send a media file over a peer connection, in place of a camera and a microphone. See the [Media Files](/guide/media/media-files) guide for the API it uses.

**Key features demonstrated:**
- Opening a media file, or a live RTSP stream, with a `MediaFileSource`
- Reading what the source contains from its `MediaInfo`
- Sending the media sources it feeds as [Client API](/guide/client-api) tracks, with `videoTrack` and `audioTrack`
- Receiving them on a second peer, connected through a loopback signaling
- Following playback through a `MediaPlayerListener`
- Closing the tracks before the source, which owns what they were made from

This example is useful for applications that stream pre-recorded media, relay an IP camera into WebRTC, or need a dependable stand-in for a camera in testing.

::: info
This example needs the `webrtc-java-media` module, which builds FFmpeg from the `third-party/ffmpeg` submodule. Make sure the submodule is checked out before building, as the [Media Files](/guide/media/media-files) guide describes.

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.MediaFileExample" -D"exec.args=movie.mp4"
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.MediaFileExample" -D"exec.args=rtsp://camera.local/stream1"
```
:::

## Media File Player

The [`MediaFilePlayerExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/MediaFilePlayerExample.java) is a Swing application built on the [Client API](/guide/client-api). It sends a media file, or a live RTSP stream, from one peer to another in the same process, then shows the received video and plays the received audio in sync, and records what is received. Start and Stop buttons set up and tear down the whole session, and Record starts and stops a recording.

**Key features demonstrated:**
- Sending the sources of a `MediaFileSource` as client API tracks, with `videoTrack` and `audioTrack`
- Connecting two peers in one application through a loopback signaling
- Using one runtime in the `CUSTOM` audio mode, which sends the file's audio and plays what is received
- Running the peers' handlers on the Swing event dispatch thread
- Keeping audio and video in sync by sending both tracks in the same media stream
- Raising the video sender's maximum bitrate once it is connected, so a high-resolution file is not held to a fraction of its size
- Rendering received video in the Swing `VideoView`
- Showing the source's format, the playback position, and live receive metrics (codec, resolution, frame rate, bitrate, packet loss, jitter, audio level) read from `onStats`
- Recording the received tracks into a Matroska file with `MediaRecorder`
- Closing the peers, then the tracks, then the source on Stop

::: info
Like the [Media File](#media-file) example, this one needs the `webrtc-java-media` module. The source argument is optional; a file can also be chosen from the window, or a stream URL typed into it. A stream URL's credentials are never shown, and the Loop box is disabled while a live stream plays, since it has no length to start over from.

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.MediaFilePlayerExample" -D"exec.args=movie.mp4"
```
:::

## JavaFX Camera

The [`JavaFxCameraExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/JavaFxCameraExample.java) is a JavaFX application built on the [Client API](/guide/client-api). It sends the camera from one peer to another in the same process, and shows the camera's preview beside the video that arrives.

**Key features demonstrated:**
- Running the peers' handlers on the JavaFX Application Thread, with `Platform::runLater` as the event executor
- Showing a local preview and a remote track in the JavaFX `VideoView` of the `webrtc-java-javafx` module
- Mirroring the preview through the view's `mirroredProperty()`
- Switching the camera while it is being sent, without renegotiating
- Showing the connection state, and the encoded resolution and frame rate read from `onStats`
- Closing the runtime off the JavaFX thread before JavaFX exits

```bash
mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.JavaFxCameraExample"
```

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