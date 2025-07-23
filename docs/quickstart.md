# Quickstart Guide

This guide will help you get started with webrtc-java quickly. We'll cover installation, basic setup, and simple examples to demonstrate core functionality.

## Supported Platforms
Maven Central artifacts contain native libraries that can be loaded on the following platforms:

<table>
  <tr>
    <td></td>
    <td>x64</td>
    <td>arm</td>
    <td>arm64</td>
  </tr>
  <tr align="center">
    <th>Linux</th>
    <td>✔</td>
    <td>✔ armeabi-v7a</td>
    <td>✔ arm64-v8a</td>
  </tr>
  <tr align="center">
    <th>macOS</th>
    <td>✔</td>
    <td>-</td>
    <td>✔</td>
  </tr>
  <tr align="center">
    <th>Windows</th>
    <td>✔</td>
    <td>-</td>
    <td>-</td>
  </tr>
</table>

## Installation

### Maven

Add the following dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>{{VERSION}}</version>
</dependency>
```

#### Using SNAPSHOT Versions

If you want to use the latest development version, you can use a SNAPSHOT release:

```xml
<dependency>
    <groupId>dev.onvoid.webrtc</groupId>
    <artifactId>webrtc-java</artifactId>
    <version>{{VERSION_SNAPSHOT}}</version>
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

```gradle
implementation "dev.onvoid.webrtc:webrtc-java:{{VERSION}}"
```

For specific platforms, add the appropriate classifier:

```gradle
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{VERSION}}", classifier: "windows-x86_64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{VERSION}}", classifier: "macos-x86_64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{VERSION}}", classifier: "macos-aarch64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{VERSION}}", classifier: "linux-x86_64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{VERSION}}", classifier: "linux-aarch64"
implementation group: "dev.onvoid.webrtc", name: "webrtc-java", version: "{{VERSION}}", classifier: "linux-aarch32"
```

## Basic Setup

### Initialize the WebRTC Library

First, create a `PeerConnectionFactory` which is the entry point for most WebRTC operations:

```java
import dev.onvoid.webrtc.PeerConnectionFactory;

// Create a peer connection factory
PeerConnectionFactory factory = new PeerConnectionFactory();
```

### Create a Peer Connection

To establish communication with another peer, you need to create a `RTCPeerConnection`:

```java
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.PeerConnectionObserver;

// Configure ICE servers (STUN/TURN)
RTCConfiguration config = new RTCConfiguration();
RTCIceServer iceServer = new RTCIceServer();
iceServer.urls.add("stun:stun.l.google.com:19302");
config.iceServers.add(iceServer);

// Create a peer connection with an observer to handle events
RTCPeerConnection peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver() {
    // Implement required methods
});
```

## Signaling

WebRTC requires a signaling mechanism to exchange connection information between peers. The library doesn't provide this, so you'll need to implement it using your preferred method (WebSockets, HTTP, etc.).

Here's a simplified example of the signaling process:

```java
// Create an offer
RTCOfferOptions options = new RTCOfferOptions();

peerConnection.createOffer(options, new CreateSessionDescriptionObserver() {
    @Override
    public void onSuccess(RTCSessionDescription description) {
        // Set local description
        peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                // Send the offer to the remote peer via your signaling channel
                signalingChannel.send(description);
            }
            
            @Override
            public void onFailure(String error) {
                System.err.println("Failed to set local description: " + error);
            }
        });
    }
    
    @Override
    public void onFailure(String error) {
        System.err.println("Failed to create offer: " + error);
    }
});

// When you receive an answer from the remote peer via your signaling channel
signalingChannel.onMessage(message -> {
    RTCSessionDescription remoteDescription = parseSessionDescription(message);
    peerConnection.setRemoteDescription(remoteDescription, new SetSessionDescriptionObserver() {
        @Override
        public void onSuccess() {
            System.out.println("Remote description set successfully");
        }
        
        @Override
        public void onFailure(String error) {
            System.err.println("Failed to set remote description: " + error);
        }
    });
});

// Handle ICE candidates
PeerConnectionObserver connectionObserver = new PeerConnectionObserver() {
    @Override
    public void onIceCandidate(RTCIceCandidate candidate) {
        // Send the ICE candidate to the remote peer via your signaling channel
        signalingChannel.send(candidate);
    }
};

// When you receive an ICE candidate from the remote peer
signalingChannel.onIceCandidate(candidateMessage -> {
    RTCIceCandidate candidate = parseIceCandidate(candidateMessage);
    peerConnection.addIceCandidate(candidate);
});
```

## Media Streams

### Accessing Media Devices

To query media devices (cameras and microphones):

```java
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.VideoDevice;
import dev.onvoid.webrtc.media.audio.AudioDevice;

// Get available video devices
List<VideoDevice> videoDevices = MediaDevices.getVideoCaptureDevices();
for (VideoDevice device : videoDevices) {
    System.out.println("Video device: " + device.getName());
}

// Get available audio devices
List<AudioDevice> audioDevices = MediaDevices.getAudioCaptureDevices();
for (AudioDevice device : audioDevices) {
    System.out.println("Audio device: " + device.getName());
}
```

### Creating Media Tracks

To create audio and video tracks:

```java
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.audio.AudioDeviceSource;

// Create a video source and track
VideoDeviceSource videoSource = new VideoDeviceSource();
videoSource.setVideoCaptureDevice(videoDevices.get(0)); // Use the first available camera
VideoTrack videoTrack = factory.createVideoTrack("video0", videoSource);

// Create an audio source and track
AudioOptions audioOptions = new AudioOptions();
audioOptions.echoCancellation = true;
audioOptions.autoGainControl = true;
audioOptions.noiseSuppression = true;

// Create an audio source using the default audio device
AudioTrackSource audioSource = factory.createAudioSource(audioOptions);
AudioTrack audioTrack = factory.createAudioTrack("audio0", audioSource);
```

> Audio devices can be specified via the `AudioDeviceModule`, see the guide on [Audio Devices](guide/audio_devices) for more details.

### Adding Tracks to Peer Connection

```java
import java.util.ArrayList;
import java.util.List;

// Add tracks to the peer connection
List<String> streamIds = new ArrayList<>();
streamIds.add("stream1");
peerConnection.addTrack(videoTrack, streamIds);
peerConnection.addTrack(audioTrack, streamIds);
```

## Cleanup

Always properly dispose of resources when you're done:

```java
// Dispose of tracks
videoTrack.dispose();
audioTrack.dispose();

// Dispose of sources
videoSource.dispose();

// Close peer connection and release resources
peerConnection.close();

// Dispose of factory
factory.dispose();
```

## Next Steps

Now that you have a basic understanding of webrtc-java, you can explore more advanced features:

- [Data Channels](guide/data_channels) - Learn more about data channels
- [RTC Stats](guide/rtc_stats) - Monitor connection quality
- [Desktop Capture](guide/desktop_capture) - Share screens and windows
- [All Guides](guide) - Complete list of guides

For a complete API reference, check the [JavaDoc](https://javadoc.io/doc/dev.onvoid.webrtc/webrtc-java/latest/index.html).