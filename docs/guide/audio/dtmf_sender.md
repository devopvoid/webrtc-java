# DTMF Sender

This guide explains how to use the DTMF (Dual-Tone Multi-Frequency) sender functionality with the webrtc-java library. DTMF senders allow you to send DTMF tones over WebRTC audio connections, which is useful for interactive voice response (IVR) systems and telephony applications.

## Overview

WebRTC DTMF senders allow you to:
- Send DTMF tones over an established audio connection
- Configure tone duration and inter-tone gap
- Monitor tone transmission events
- Check if DTMF tones can be inserted

DTMF tones are the audible tones generated when pressing keys on a telephone keypad. The supported DTMF tones are: 0-9, A-D, *, and #. In addition, the special character ',' (comma) can be used to insert a 2-second delay between tones.

## Getting a DTMF Sender

To use DTMF functionality, you need an established `RTCPeerConnection` with an audio track. You can then get the DTMF sender from the RTP sender associated with the audio track:

```java
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCDtmfSender;
import dev.onvoid.webrtc.media.audio.AudioTrack;

// Assuming you already have a PeerConnectionFactory and RTCConfiguration
RTCPeerConnection peerConnection = factory.createPeerConnection(config, peerConnectionObserver);

// Create and add an audio track
AudioTrackSource audioSource = factory.createAudioSource(new AudioOptions());
AudioTrack audioTrack = factory.createAudioTrack("audioTrack", audioSource);

// Add the track to the peer connection
List<String> streamIds = new ArrayList<>();
streamIds.add("stream1");
RTCRtpSender sender = peerConnection.addTrack(audioTrack, streamIds);

// Get the DTMF sender
RTCDtmfSender dtmfSender = sender.getDtmfSender();
```

## Checking DTMF Capability

Before attempting to send DTMF tones, you should check if the DTMF sender is capable of sending tones:

```java
if (dtmfSender != null && dtmfSender.canInsertDtmf()) {
    // DTMF is supported and can be used
    System.out.println("DTMF is supported");
} else {
    // DTMF is not supported
    System.out.println("DTMF is not supported");
}
```

The `canInsertDtmf()` method returns true if and only if:
- The associated RTCRtpSender's track is non-null and is of kind "audio"
- The RTCDtmfSender is able to send packets
- A "telephone-event" codec has been negotiated

## Sending DTMF Tones

To send DTMF tones, use the `insertDtmf` method:

```java
// Send DTMF tones with custom duration (100ms) and inter-tone gap (70ms)
boolean success = dtmfSender.insertDtmf("123", 100, 70);
```

The `insertDtmf` method takes the following parameters:
- `tones`: A string containing the DTMF tones to send
- `duration`: The duration in milliseconds for each tone (default: 100ms)
- `interToneGap`: The gap between tones in milliseconds (default: 50ms)

The method returns `true` if the tones were successfully queued for transmission, or `false` if the operation failed.

### Valid Tones

The following characters are valid in the `tones` parameter:
- Digits: 0-9
- Letters: A-D (or a-d, case-insensitive)
- Symbols: * (asterisk), # (pound/hash)
- Special: , (comma) - inserts a 2-second delay

Unrecognized characters are ignored.

### Duration and Inter-Tone Gap Constraints

The duration and inter-tone gap parameters have the following constraints:
- Duration must be between 70ms and 6000ms (default: 100ms)
- Inter-tone gap must be at least 50ms (default: 50ms)

If these constraints are not met, the `insertDtmf` method will return `false`.

## Monitoring DTMF Events

To receive notifications about DTMF tone events, implement the `RTCDtmfSenderObserver` interface and register it with the DTMF sender:

```java
import dev.onvoid.webrtc.RTCDtmfSenderObserver;

dtmfSender.registerObserver(new RTCDtmfSenderObserver() {
    @Override
    public void onToneChange(String tone, String toneBuffer) {
        if (tone == null || tone.isEmpty()) {
            System.out.println("All tones have been played");
        } else {
            System.out.println("Playing tone: " + tone);
            System.out.println("Remaining tones: " + toneBuffer);
        }
    }
});
```

The `onToneChange` method is called:
- When a new tone starts playing, with the tone character and the remaining tones buffer
- When all tones have finished playing, with an empty string for both parameters

## Getting DTMF Properties

You can query various properties of the DTMF sender:

```java
// Get the tones currently in the queue
String remainingTones = dtmfSender.tones();

// Get the current duration setting
int duration = dtmfSender.duration();

// Get the current inter-tone gap setting
int interToneGap = dtmfSender.interToneGap();
```

## Cleanup

When you're done with the DTMF sender, you should unregister any observers:

```java
// Unregister the observer
dtmfSender.unregisterObserver();
```

Note that you don't need to explicitly dispose of the DTMF sender, as it will be cleaned up when the associated RTP sender is disposed.

## Best Practices

1. **Check Capability**: Always check if DTMF is supported using `canInsertDtmf()` before attempting to send tones.

2. **Error Handling**: Check the return value of `insertDtmf()` to ensure the tones were successfully queued.

3. **Observer Cleanup**: Always unregister observers when you're done to prevent memory leaks.

4. **Tone Duration**: Use appropriate tone durations based on your application needs:
   - For standard telephony applications, the default 100ms is usually sufficient
   - For IVR systems that might need more processing time, consider longer durations

5. **Tone Buffering**: Be aware that tones are queued and played sequentially. If you need to cancel queued tones, you can call `insertDtmf("")` to clear the queue.

---

## Conclusion

The RTCDtmfSender provides a standard way to send DTMF tones over WebRTC audio connections. This functionality is particularly useful for applications that need to interact with traditional telephony systems, IVR systems, or any service that uses DTMF for signaling.

By following the guidelines in this document, you can effectively integrate DTMF functionality into your WebRTC applications, enabling users to interact with automated systems or trigger actions using their device's keypad.

For more information on other WebRTC features, refer to the additional guides in the documentation.