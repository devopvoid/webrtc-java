# Examples

This section provides an overview of the example applications included in the `webrtc-examples` Maven module. These examples demonstrate various features and capabilities of the webrtc-java library.

## PeerConnectionExample

The `PeerConnectionExample` demonstrates how to set up a peer connection with audio and video tracks to be able to send and receive media.

**Key features demonstrated:**
- Creating a PeerConnectionFactory
- Creating audio and video tracks
- Setting up a peer connection
- Adding tracks to the peer connection for sending media
- Implementing callbacks to receive incoming audio and video frames

This example provides a foundation for building WebRTC applications that need to handle audio and video communication.

## WhepExample

The `WhepExample` demonstrates an implementation of WebRTC HTTP Egress Protocol (WHEP) client, which is a standardized protocol for WebRTC ingestion.

**Key features demonstrated:**
- Setting up a WebRTC peer connection
- Creating and sending an SDP offer to a WHEP endpoint
- Receiving and processing an SDP answer
- Establishing media streaming over WebRTC

This example is useful for applications that need to receive media streams from WHEP-compatible servers, such as live streaming platforms.

## CodecListExample

The `CodecListExample` demonstrates how to list all supported codecs with the WebRTC peer-connection-factory.

**Key features demonstrated:**
- Creating a PeerConnectionFactory
- Getting the supported codecs for both sending and receiving audio and video
- Displaying detailed information about each codec

This example is useful for understanding what codecs are available on the current system, which can help with debugging compatibility issues or optimizing media quality.

## DesktopVideoExample

The `DesktopVideoExample` demonstrates how to set up a peer connection with a desktop video source for screen or window capture.

**Key features demonstrated:**
- Creating a PeerConnectionFactory
- Getting available desktop sources (screens and windows)
- Creating a VideoDesktopSource for capturing screen or window content
- Configuring the VideoDesktopSource properties
- Creating a video track with the desktop source
- Setting up a peer connection

This example is particularly useful for applications that need to implement screen sharing or remote desktop functionality.


## Running the Examples

To run these examples, you need to include the `webrtc-examples` module in your project. You can then execute the main method of each example class.

For example, to run the `CodecListExample`:

```bash
mvn exec:java -Dexec.mainClass="dev.onvoid.webrtc.examples.CodecListExample"
```

Note that these examples focus on setting up the local components for WebRTC communication. In a real application, you would need to establish a connection with a remote peer through a signaling channel (e.g., WebSocket).