# Examples

This section provides an overview of the example applications included in the `webrtc-examples` Maven module. These examples demonstrate various features and capabilities of the webrtc-java library.

## PeerConnection

The [`PeerConnectionExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/PeerConnectionExample.java) demonstrates how to set up a peer connection with audio and video tracks to be able to send and receive media.

**Key features demonstrated:**
- Creating a PeerConnectionFactory
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
- Creating a PeerConnectionFactory
- Getting the supported codecs for both sending and receiving audio and video
- Displaying detailed information about each codec

This example is useful for understanding what codecs are available on the current system, which can help with debugging compatibility issues or optimizing media quality.

## Desktop Video

The [`DesktopVideoExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/DesktopVideoExample.java) demonstrates how to set up a peer connection with a desktop video source for screen or window capture.

**Key features demonstrated:**
- Creating a PeerConnectionFactory
- Getting available desktop sources (screens and windows)
- Creating a VideoDesktopSource for capturing screen or window content
- Configuring the VideoDesktopSource properties
- Creating a video track with the desktop source
- Setting up a peer connection

This example is particularly useful for applications that need to implement screen sharing or remote desktop functionality.

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

**Note:** Before running the WebClientExample, you need to start the WebServer first. The WebServer provides the signaling service required for WebRTC connection establishment:

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