# Core API

The core API in `dev.onvoid.webrtc` mirrors WebRTC's own classes one to one. It leaves negotiation, threads and the lifetime of native objects to you. The steps below make the call of [Getting Started](/guide/get-started#your-first-call) with it; the [Client API](/guide/client-api) does all of this for you.

## Initialize the WebRTC Library

First, create a `PeerConnectionFactory` which is the entry point for most WebRTC operations:

```java
import dev.onvoid.webrtc.PeerConnectionFactory;

// Create a peer connection factory
PeerConnectionFactory factory = new PeerConnectionFactory();
```

## Create a Peer Connection

To establish communication with another peer, you need to create a `RTCPeerConnection`:

```java
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
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
    @Override
    public void onIceCandidate(RTCIceCandidate candidate) {
        // Send the candidate to the remote peer, see below. The other
        // methods are optional.
    }
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

### Letting the Peer Connection Create the Description

`setLocalDescription` can also be called with only an observer. It then creates the offer or the answer, whichever the signaling state calls for, and applies it in one step, so nothing can change in between. That makes it the simpler way to implement "perfect negotiation". Read the result with `getLocalDescription()` once the observer reports success:

```java
peerConnection.setLocalDescription(new SetSessionDescriptionObserver() {
    @Override
    public void onSuccess() {
        signalingChannel.send(peerConnection.getLocalDescription());
    }

    @Override
    public void onFailure(String error) {
        System.err.println("Failed to set local description: " + error);
    }
});
```

### Adding Candidates and Knowing the Outcome

`addIceCandidate(candidate)` reports nothing and fails silently when the remote description is not set yet. The form that takes an `AddIceCandidateObserver` is queued behind any pending offer/answer operation, as the standard requires, so a candidate can be added right after `setRemoteDescription()` without waiting for it, and the observer says whether it was accepted:

```java
peerConnection.addIceCandidate(candidate, new AddIceCandidateObserver() {
    @Override
    public void onSuccess() {
    }

    @Override
    public void onFailure(String error) {
        // e.g. "[INVALID_STATE] The remote description was null"
        System.err.println("Candidate rejected: " + error);
    }
});
```

A local candidate can also go away, for example when its network interface does. The observer's `onIceCandidateRemoved(RTCIceCandidate)` reports it; send it to the remote peer, which passes it to `removeIceCandidate(candidate)`.

Once ICE has picked the candidate pair it uses, `onSelectedCandidatePairChanged(RTCCandidatePairChangeEvent)` reports both candidates of the pair and why it was chosen. It fires again whenever ICE switches pairs:

```java
@Override
public void onSelectedCandidatePairChanged(RTCCandidatePairChangeEvent event) {
    System.out.println("Using " + event.getLocal().sdp + " <-> " + event.getRemote().sdp
            + " (" + event.getReason() + ")");
}
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
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

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

::: info
Audio devices can be specified via the `AudioDeviceModule`, see the guide on [Audio Devices][audio-devices] for more details.
:::

### Adding Tracks to Peer Connection

```java
import java.util.ArrayList;
import java.util.List;
import dev.onvoid.webrtc.RTCRtpSender;

// Add tracks to the peer connection
List<String> streamIds = new ArrayList<>();
streamIds.add("stream1");
RTCRtpSender videoSender = peerConnection.addTrack(videoTrack, streamIds);
RTCRtpSender audioSender = peerConnection.addTrack(audioTrack, streamIds);
```

### Receiving Tracks

A remote track arrives in `onTrack` with the transceiver that carries it. To learn when its media actually starts flowing, for example to show a video only once there is something to show, set an `RTCRtpReceiverObserver`; an `RTCRtpSenderObserver` does the same for the first packet a sender sends. Either is called right away when that has already happened:

```java
@Override
public void onTrack(RTCRtpTransceiver transceiver) {
    RTCRtpReceiver receiver = transceiver.getReceiver();

    receiver.setObserver(mediaType -> System.out.println("First " + mediaType + " packet received"));

    MediaStreamTrack track = receiver.getTrack();

    if (track instanceof AudioTrack) {
        // Playout volume of this remote track, 0 to 10; 1 plays it as received.
        ((AudioTrack) track).setVolume(0.5);
    }
}
```

An observer belongs to the `RTCRtpReceiver` or `RTCRtpSender` object it was set through, and is removed when that object is disposed. `getId()` identifies a sender or receiver, and `RTCRtpSender.getStreams()` returns the stream IDs its track was added with.

## Cleanup

Always properly dispose of resources when you're done:

```java
// Dispose of senders (not owned by the peer connection)
videoSender.dispose();
audioSender.dispose();

// Dispose of tracks
videoTrack.dispose();
audioTrack.dispose();

// Dispose of sources
videoSource.dispose();
audioSource.dispose();

// Close peer connection and release resources
peerConnection.close();

// Dispose of factory
factory.dispose();
```

A track may be disposed, and a listener removed, from within one of its listeners: the listeners the notification has not reached yet are then not called, and what goes is freed once the notification is over. An observer registered on one thread while the object is disposed on another is freed once and never left registered. A factory disposed while a closed connection is still being freed is released once that connection is.

`dispose()` and `close()` may be called from any thread and more than once: only the first call frees anything, later ones do nothing. Once an object is disposed, its methods throw an `IllegalStateException` (a closed connection still reports its state). A method that another thread is running at that moment keeps the native object until it returns, and what the dispose frees is freed then.

Apart from the media sources, which only have `dispose()`, objects with a `dispose()` method also have a `close()` that does the same. A try-with-resources statement therefore releases them, also when an exception leaves the block:

```java
try (AudioConverter converter = new AudioConverter(48000, 1, 16000, 1)) {
    converter.convert(input, output);
}
```

`RTCDataChannel` is the exception: its `close()` closes the channel, which then still has to be disposed.

### Finding Objects That Were Never Disposed

An object that becomes unreachable without being disposed or closed leaks its native object. To find such objects, start the JVM with `-Dwebrtc.java.leak.detection=true`: each one is then reported, with its class, once the garbage collector finds it unreachable. Add `-Dwebrtc.java.leak.trace=true` to have the report tell where the object was created, at the cost of a stack trace per object.

```shell
java -Dwebrtc.java.leak.detection=true -Dwebrtc.java.leak.trace=true -jar app.jar
```

The reports are warnings through `Logging`, which writes nothing unless the application asks for it:

```java
Logging.logToDebug(Logging.Severity.WARNING);
```

Only objects that own a native object and have a `dispose()` or `close()` method are watched: not views such as the track of `RTCRtpSender.getTrack()`, which own nothing, and not reference-counted objects such as video frame buffers. The detector only reports; it never frees anything itself, since a native object may still be in use on a WebRTC thread. While it is off, which is the default, nothing is watched.
