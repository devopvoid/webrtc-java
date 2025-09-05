# Media Directionality

This guide explains how to configure WebRTC media to be receive-only or send-only using the webrtc-java API. It also shows the equivalent configuration on the JavaScript (browser) side. You will learn how to:

- Create receive-only audio/video transceivers
- Create send-only audio/video transceivers
- Change direction at runtime
- Understand the underlying SDP attributes (a=sendonly / a=recvonly / a=inactive)

The examples build upon the RTCRtpTransceiver API, which is the recommended way to control directionality in modern WebRTC.

Related API:
- dev.onvoid.webrtc.RTCRtpTransceiver
- dev.onvoid.webrtc.RTCRtpTransceiverInit
- dev.onvoid.webrtc.RTCRtpTransceiverDirection

References in the repository:
- Tests: [RTCPeerConnectionTests.java](https://github.com/devopvoid/webrtc-java/blob/main/webrtc/src/test/java/dev/onvoid/webrtc/RTCPeerConnectionTests.java)
- Example (receive-only with WHEP): [WhepExample.java](https://github.com/devopvoid/webrtc-java/blob/main/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/WhepExample.java)

## Concepts overview

- SEND_RECV: Both sending and receiving are active (default when you add a track).
- SEND_ONLY: Only sending is negotiated; you won’t receive media on this transceiver.
- RECV_ONLY: Only receiving is negotiated; you won’t send media on this transceiver.
- INACTIVE: Neither sending nor receiving on this transceiver.

These map to the SDP attributes a=sendrecv, a=sendonly, a=recvonly, a=inactive.

## Receive-only example

Use a transceiver with direction RECV_ONLY to indicate that you only want to receive media for a given kind (audio or video). You can optionally pass a dummy local track or omit sending entirely by not attaching a sending track.

```java
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

PeerConnectionFactory factory = new PeerConnectionFactory();
RTCConfiguration config = new RTCConfiguration();
RTCPeerConnection pc = factory.createPeerConnection(config, candidate -> {});

// Create a video track/source (can be a dummy source when only receiving)
VideoDeviceSource videoSource = new VideoDeviceSource();
VideoTrack videoTrack = factory.createVideoTrack("videoTrack", videoSource);

// Configure transceiver as RECV_ONLY
RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
init.direction = RTCRtpTransceiverDirection.RECV_ONLY;
RTCRtpTransceiver transceiver = pc.addTransceiver(videoTrack, init);

// Access the receiving track and attach a sink
MediaStreamTrack track = transceiver.getReceiver().getTrack();
if (track instanceof dev.onvoid.webrtc.media.video.VideoTrack vTrack) {
    vTrack.addSink(frame -> {
        // Handle incoming frames
        System.out.println("Received frame: " + frame);
        frame.release();
    });
}
```

Notes:
- This pattern is used in the WhepExample included in the repository.
- When you create the offer, the SDP will contain a=recvonly for that m= section.

## Send-only example

To send-only, set the transceiver direction to SEND_ONLY and provide a local track to send.

```java
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

PeerConnectionFactory factory = new PeerConnectionFactory();
RTCPeerConnection pc = factory.createPeerConnection(new RTCConfiguration(), candidate -> {});

// Create an audio track to send
AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

// Configure transceiver as SEND_ONLY
RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
init.direction = RTCRtpTransceiverDirection.SEND_ONLY;
RTCRtpTransceiver transceiver = pc.addTransceiver(audioTrack, init);

// Optionally verify
assert transceiver.getDirection() == RTCRtpTransceiverDirection.SEND_ONLY;
```

When you create the offer with this setup, the SDP will include a=sendonly for the audio m= section.

## Changing direction at runtime

You can change the direction dynamically. Remember that direction changes typically require renegotiation (createOffer/setLocalDescription -> signal -> setRemoteDescription).

```java
RTCRtpTransceiver transceiver = /* previously created */;

// Change to INACTIVE
transceiver.setDirection(RTCRtpTransceiverDirection.INACTIVE);

// Later switch to SEND_RECV
transceiver.setDirection(RTCRtpTransceiverDirection.SEND_RECV);

// After changing directions, create a new offer and perform negotiation.
RTCOfferOptions opts = new RTCOfferOptions();
pc.createOffer(opts, new CreateSessionDescriptionObserver() {
    @Override
    public void onSuccess(RTCSessionDescription description) {
        pc.setLocalDescription(description, /* observer */ null);
        // Send to remote and await/set remote answer accordingly
    }
    @Override
    public void onFailure(String error) { /* handle error */ }
});
```

> Tip: You can also control sending without renegotiation by replacing the sender’s track or disabling it via MediaStreamTrack.setEnabled(false). However, the negotiated direction in SDP remains the same until you renegotiate.

## Common patterns and tips

- If you only need to receive a stream from a server (e.g., WHEP), use RECV_ONLY and avoid capturing local devices. This simplifies permissions and reduces CPU usage.
- To temporarily stop sending without renegotiation, you can disable the sender’s track: sender.getTrack().setEnabled(false).
- Use INACTIVE when neither sending nor receiving should occur on a transceiver, but you want to keep it for future use.
- Direction changes typically require a new offer/answer exchange.

## Troubleshooting

- No remote media arriving in RECV_ONLY mode:
  - Ensure the remote endpoint actually sends media on that m= section.
  - Verify codecs overlap (see CodecListExample in examples).
  - Check network/firewall and ICE connectivity.
- Permissions prompts appear even in receive-only mode:
  - Avoid creating real capture devices if you don’t need to send. You can add a transceiver with a dummy track.
- SDP direction not as expected:
  - Confirm the transceiver direction before creating the offer.
  - Some changes only apply after renegotiation.
