# Encoded Transforms

This guide explains how to read and change the encoded frames of a call as they pass through an `RTCRtpSender` or an `RTCRtpReceiver`, the way [WebRTC Encoded Transforms](https://www.w3.org/TR/webrtc-encoded-transform/) (also known as insertable streams) do in a browser. It covers:

- Setting a transform on a sender or a receiver
- Reading and replacing a frame's payload
- Dropping frames
- What each frame tells about itself
- Asking for key frames
- End-to-end encryption as a worked example
- Threading and performance

A sender's transform sees every frame after the encoder and before the packetizer; a receiver's sees every frame after the depacketizer and before the decoder. Everything between the two, whether that is the network, a TURN server or an SFU, only ever carries what the transform produced. That is what makes end-to-end encryption possible, and also frame metadata, watermarks, or analysis of the encoded stream.

## Setting a Transform

An `RTCEncodedFrameTransformer` is a functional interface with a single method, `transform(RTCEncodedFrame frame)`. It changes the frame in place, and the frame is sent on when the method returns:

```java
RTCRtpSender sender = peerConnection.addTrack(videoTrack, List.of("stream"));

sender.setTransform(frame -> {
    System.out.println(frame);   // mime type, size, key frame, timestamp, SSRC
});
```

On the receiving side, the receivers are there once the remote description is set:

```java
for (RTCRtpReceiver receiver : peerConnection.getReceivers()) {
    receiver.setTransform(frame -> inspect(frame));
}
```

`setTransform(null)` removes the transform again, after which frames pass unchanged.

::: tip
Set the transform of a video sender before negotiating when you can. The first transform set on a running video sender restarts its send stream, which costs a key frame. Later changes, including removing it, cost nothing.
:::

The transform belongs to the native sender or receiver, not to the Java object: every `RTCRtpSender` instance for the same sender shares it, setting it through one replaces what was set through another, and disposing of an instance leaves it in place.

## Reading and Changing the Payload

`getData()` returns the payload in a `ByteBuffer`, from position zero to its size. The buffer holds a copy, which may be changed freely; `setData(...)` then writes it back:

```java
receiver.setTransform(frame -> {
    ByteBuffer data = frame.getData();

    for (int i = 0; i < data.limit(); i++) {
        data.put(i, (byte) (data.get(i) ^ 0x5A));
    }

    frame.setData(data);
});
```

`setData` takes a `ByteBuffer` (its remaining bytes) or a `byte[]`, and the new payload may be larger or smaller than the old one. The payload is only copied into Java when `getData()` is called, so a transform that looks at the metadata alone costs no copy at all.

A frame, and the buffer `getData()` returned, is valid only while `transform` runs and only on its thread. The buffer's memory is reused for the next frame, so copy what you need to keep. Reading or changing a frame after the transform returned throws an `IllegalStateException`; its metadata stays readable.

## Dropping Frames

`frame.drop()` releases the frame instead of sending it on. A transform that throws drops the frame as well, and the exception goes to the thread's uncaught exception handler. It is dropped rather than sent on unchanged because an encryption transform that failed must never send the frame in the clear.

A receiver cannot decode a video frame that depends on one it never got, so dropping video frames usually means waiting for the next key frame.

## Frame Metadata

Every `RTCEncodedFrame` tells its `getMimeType()` (e.g. `video/VP8`, `audio/opus`), `getSize()`, RTP `getTimestamp()`, `getSsrc()`, `getPayloadType()` and `getCaptureTimeUs()`. The subclasses add what is particular to their kind:

| `RTCEncodedVideoFrame` | `RTCEncodedAudioFrame` |
| --- | --- |
| `isKeyFrame()` | `getSequenceNumber()` (received frames) |
| `getWidth()`, `getHeight()` (key frames) | `getAudioLevel()` in -dBov |
| `getRid()` (simulcast layer) | `getContributingSources()` |
| `getFrameId()`, `getSpatialIndex()`, `getTemporalIndex()` | |

## Key Frames

A transform that needs a key frame, e.g. to start over after a key change, can ask for one:

```java
videoSender.generateKeyFrame();    // the local encoder makes the next frame a key frame
videoReceiver.requestKeyFrame();   // asks the remote sender for one
```

Both do nothing for audio.

## End-to-End Encryption

A complete example with AES-GCM is in [`EncryptedRecordingExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/EncryptedRecordingExample.java). Two points matter for any encrypting transform:

- **Keep the codec header in the clear.** The packetizer and depacketizer read the start of each frame: a VP8 receiver finds key frames and the frame size in the first bytes. Browsers leave 10 bytes of a VP8 key frame, 3 of a VP8 delta frame and 1 of an Opus frame unencrypted, and authenticate them as associated data instead. H.264 is split at its NAL unit start codes before it is sent, so encrypting it whole breaks the packetizer; prefer VP8, VP9 or AV1 for encrypted calls, or leave the NAL unit headers in the clear.
- **Make each frame self-contained.** Frames are lost, so each needs what it takes to be decrypted, such as its IV, and a receiver must drop a frame that fails to authenticate rather than pass it on.

## Threading and Performance

The transform never runs on a thread that carries media. Each sender and receiver with a transform gets a thread of its own. Frames are queued to that thread and transformed there one at a time, in order, and are handed back to WebRTC from it. This means that:

- a slow transform delays only its own frames, never the connection;
- a transform may call back into the peer connection, even close it, without deadlocking;
- a transform that falls several seconds behind has frames dropped until it catches up, rather than having them queue up in memory.

Per frame, the cost is one small Java object and, only if the payload is read, one copy into a buffer that is reused from frame to frame. Senders and receivers without a transform are not touched at all.
