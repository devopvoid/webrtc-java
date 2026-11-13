# Transforms and Encryption

An [encoded transform](/guide/advanced/encoded-transforms) sees every encoded frame between the encoder and the network, or between the network and the decoder. That is where end-to-end encryption goes. The transforms of the peer options apply to every sender and every remote track of the peer:

```java
Peer peer = rtc.createPeer(PeerOptions.builder()
        .signaling(signaling)
        .sendTransform(frame -> encrypt(frame))
        .receiveTransform(frame -> decrypt(frame))
        .build());
```

The peer sets the send transform on each sender as its track is added, before negotiation. No frame leaves without it, and the encoder needs no restart.

The peer sets the receive transform on each receiver before `onTrack` hands out the track, so the transform is in place before the first frame arrives. What happens when it cannot be installed is described under [Details](#installing-the-receive-transform).

The builders of [WHIP and WHEP](/guide/client/whip-whep) take transforms too: `Whip`'s as `sendTransform(...)`, `Whep`'s as `receiveTransform(...)`.

## End-to-End Encryption

For end-to-end encryption, `e2ee(keys)` takes the place of both transforms. Every sender gets an encryptor and every remote track a decryptor of the [key provider](/guide/advanced/end-to-end-encryption). The key provider also takes care of key rotation, replay protection, frames that fail and key frame requests:

```java
PeerOptions.builder()
        .signaling(signaling)
        .e2ee(keys)
        .build();
```

## One Sender or Track

A single sender or remote track takes a transform of its own, in place of the peer's, and `null` removes it:

```java
videoSender.transform(frame -> addMetadata(frame));
remoteTrack.transform(null);                   // frames reach the decoder unchanged
```

- The first transform set on a video sender that already sends restarts its encoder, which costs a key frame.
- A transform set on a remote track misses the frames that arrive before its future completes. A transform that every frame needs, such as decryption, therefore belongs in the peer options.
- A recording of a sender holds its frames before the transform, a recording of a remote track after it.

## Key Frames

A transform that changes its key needs a key frame to start over with, since the frames before depend on the old key:

```java
videoSender.generateKeyFrame();                // the local encoder makes the next frame a key frame
remoteTrack.asVideo().requestKeyFrame();       // asks the remote sender for one
```

`generateKeyFrame()` does nothing for an audio sender, and nothing before the peer is negotiated, as the stream starts with a key frame anyway. `requestKeyFrame()` fails once the track ended.

## Threads and Failures

Transforms run on a thread of each sender and receiver, not on the peer's event thread.

A frame that a transform throws on is dropped rather than passed on, so a failed encryption never sends a frame in the clear. The first such failure of each sender or receiver goes to the peer's `onError` handlers.

The [`EncryptedRecordingExample`](https://github.com/devopvoid/webrtc-java/blob/main/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/EncryptedRecordingExample.java) encrypts a call with `e2ee(keys)` and records it.

## Details

### Installing the Receive Transform

If installing the receive transform fails, the peer stops that track's transceiver and reports the failure through `onError`. The track is neither listed in `remoteTracks()` nor delivered through `onTrack`. If the receive path cannot be stopped, the peer closes.