# End-to-End Encryption

This guide explains how to encrypt a call end to end, so that the media servers, TURN servers and SFUs between the participants forward media they cannot read. It covers:

- Encrypting a call with the client API, or with senders and receivers directly
- Participants, keys and key generations
- Rotating keys
- What happens to frames that cannot be encrypted or decrypted
- Replay protection
- The codecs that can be encrypted
- Recording an encrypted call
- Encrypting a call with a browser
- The frame format, as a specification for other implementations

The [encoded transforms](/guide/advanced/encoded-transforms) are where the encryption happens. `dev.onvoid.webrtc.e2ee` adds what an encryption algorithm alone does not settle: the [SFrame](https://www.rfc-editor.org/rfc/rfc9605) format of RFC 9605, a key provider that holds the keys of every participant with their key IDs and generations, separate keys for every stream a participant sends, protection against replayed frames, a defined behavior for every frame that fails, and a recording policy.

## Encrypting a Call

Every participant of a call has an ID, unique within the call, and a `KeyProvider` that holds its own key and the keys of the others. Getting the keys to the others is up to the application (see [Distributing Keys](#distributing-keys)).

```java
KeyProvider keys = KeyProvider.builder(myId)
        .listener(event -> log.warning("Frames dropped: " + event))
        .build();

byte[] myKey = keys.generateKey();
keys.setEncryptionKey(0, myKey);
// Send (myId, 0, myKey) to the others, over a channel the servers cannot read.

// For every key another participant sends:
keys.setDecryptionKey(theirId, theirGeneration, theirKey);
```

With the [client API](/guide/client-api), the key provider goes into the peer options. The peer then encrypts every frame it sends and decrypts every frame it receives, from the first one:

```java
Peer peer = rtc.createPeer(PeerOptions.builder()
        .signaling(signaling)
        .e2ee(keys)
        .build());
```

`Whip.Builder` and `Whep.Builder` take `e2ee(keys)` as well. End-to-end encryption brings its own transforms, so the options take no `sendTransform` or `receiveTransform` besides.

Without the client API, every sender and receiver takes a transform of the key provider. Set them before negotiating, so that no frame leaves unencrypted and the encoder needs no restart:

```java
RTCRtpSender sender = peerConnection.addTrack(videoTrack, List.of("stream"));
sender.setTransform(keys.createEncryptor(sender::generateKeyFrame));

// Once the remote description is set:
for (RTCRtpReceiver receiver : peerConnection.getReceivers()) {
    receiver.setTransform(keys.createDecryptor(receiver::requestKeyFrame));
}
```

The argument of `createEncryptor` and `createDecryptor` asks for a key frame when one helps (see [Key Frames](#key-frames)). Both work without one too.

## Participants and Keys

A participant ID is a number from 0 to 2<sup>32</sup>-1, which every participant of the call knows the others by, such as the index of a member in an [MLS](https://www.rfc-editor.org/rfc/rfc9420) group or a number the application's server hands out. Each participant encrypts with a key of its own, which the others decrypt with. This is the "sender keys" scheme of RFC 9605, Section 5.1, and it follows the rule of the RFC that a key is only ever used for encryption by one participant: `setDecryptionKey` refuses the local participant's ID.

Every key belongs to a generation, 0 to 255, which goes up with each new key of a participant and wraps around after 255. A receiver keeps a participant's generations side by side, so a new key can be handed out before it is used. `generateKey()` makes a random key as long as the cipher suite's hash output; any key of at least 16 bytes works.

::: warning
Use a key for one call only, and never give it to a second `KeyProvider`. A key provider counts the frames it encrypts under each key, and a new one starts over, which would encrypt two frames under the same key and nonce.
:::

### Distributing Keys

The keys have to reach the other participants over a channel that the servers forwarding the media cannot read. That channel, and how participants authenticate each other on it, decides how secure the call is. Common choices are an MLS group, whose exporter gives a key per epoch (`MLS-Exporter("SFrame 1.0 Base Key", "", 16)`, RFC 9605, Section 5.2), or a messaging channel that is itself end-to-end encrypted. The signaling channel of the call is only good enough if the signaling server is trusted not to read it.

### Rotating Keys

A participant changes its key when someone joins or leaves the call, so that the newcomer cannot decrypt what was sent before, and the one who left cannot decrypt what is sent after. To rotate:

1. Make a new key with the next generation, and send it to the others, who `setDecryptionKey` it next to the old one.
2. Once they have it, acknowledged or after a timeout, `setEncryptionKey` it. Frames go out with the new key from the next one on.
3. Nothing more is needed on the receiving side. A receiver retires a participant's older generations 5 seconds after the first frame of a newer one decrypted (`Builder.keyRetention`), which covers the frames that were on their way during the switch. `removeDecryptionKey` and `removeParticipant` remove keys right away.

A video sender asks its encoder for a key frame with the first frame under a new key, so that a participant who has just joined, and has no key for the frames before, does not have to wait for the next one.

### Streams

Every RTP stream a participant sends, its audio, its video and every simulcast layer, gets a context of its own, and with it a key ID and a derived key of its own. Streams count their frames independently and never share a key and nonce, without the application having to give them different keys. One encryptor may serve several senders, as the client API's peer options do, since it tells the streams apart by their SSRC.

## Failed Frames

A frame that cannot be encrypted is dropped, never sent in the clear. A received frame that does not decrypt is dropped, never passed to the decoder. Each has a reason, a `CryptoFailure`:

| Failure | Sending or receiving | Means |
| --- | --- | --- |
| `MISSING_KEY` | sending | The key provider has no encryption key, or no longer. |
| `UNKNOWN_KEY` | receiving | The key the frame was encrypted with is not there: not arrived yet, removed, or retired. |
| `AUTHENTICATION` | receiving | The frame was changed on the way, or encrypted with another key of the same key ID. |
| `REPLAY` | receiving | A frame with the same counter was decrypted before, or the frame is older than the replay window. |
| `MALFORMED` | both | A received frame is no SFrame ciphertext, as when the other side does not encrypt, or a frame to send is too short for its codec's header. |
| `UNSUPPORTED_CODEC` | both | H.264 or H.265, which the frame format does not cover (see [Codecs](#codecs)). |

A stream whose key has not arrived yet drops its frames until it has, and then decrypts again from the first frame whose key is there. Frames are not held back to wait for a key, since a frame is only valid while its transform runs.

The listener of the key provider hears of a stream when it begins to fail, and again only when it fails for another reason or after frames went through in between, so a stream without its key does not report each of its frames. The event says which participant and key generation the frames need, which is what a request for a missing key takes:

```java
KeyProvider keys = KeyProvider.builder(myId)
        .listener(event -> {
            if (event.getFailure() == CryptoFailure.UNKNOWN_KEY) {
                keyChannel.requestKey(event.getParticipant(), event.getGeneration());
            }
        })
        .build();
```

The listener runs on the transform thread of the sender or receiver, so it should hand anything slow to another thread. `getStats()` counts every frame encrypted, decrypted and dropped, by reason. With the client API, failed frames go to the listener only, not to the peer's `onError` handlers.

### Key Frames

A decoder cannot decode the video frames that follow one it never got, so a video receiver that dropped frames asks the remote sender for a key frame as soon as frames decrypt again, at most twice a second. A video sender asks its own encoder for one when it starts to use a new key, and when it sends again after dropping frames for want of a key.

## Replay Protection

A receiver accepts each counter of a stream once: a frame that repeats the counter of one already decrypted, as a server that records and resends frames would produce, is dropped, and so is a frame older than the replay window. The window reaches 128 frames back by default, several seconds of any stream and far more than any reordering. `Builder.replayWindow(frames)` changes it, and 0 turns the protection off. A counter only counts as seen once its frame authenticated, so forged frames cannot move the window.

## Codecs

The frame format covers VP8, VP9, AV1 and every audio codec. WebRTC's packetizers and depacketizers read parts of some frames, so the format keeps those parts readable and authenticates them instead:

- **VP8** keeps its payload header in the clear: 10 bytes of a key frame, 3 of a delta frame. The receiver finds key frames and the frame size there.
- **Opus** keeps its first byte, the TOC byte, in the clear, as browsers do.
- **AV1** is split into OBUs by its packetizer, so the encrypted frame travels as a single OBU: a sequence header for a key frame, which is how the receiver recognizes one, and a frame OBU otherwise.
- **VP9** and other audio codecs are encrypted whole.

H.264 and H.265 frames are split at their start codes before they are sent, which a ciphertext would contain anywhere, so they are dropped with `UNSUPPORTED_CODEC`. A call has to negotiate them away, which matters on macOS in particular, where WebRTC prefers H.264 through VideoToolbox:

- A peer of the client API with `e2ee(keys)` does so by itself: it offers and answers only VP8, VP9 and AV1 video, in WebRTC's order. `videoCodecs(...)` of the [WHIP and WHEP](/guide/client/whip-whep) builders still picks among them, and fails the session with a `ConfigurationException` if it names none of them.
- Without the client API, call `setCodecPreferences` on each video transceiver with the codecs for which `KeyProvider.supportsCodec(codec.getMimeType())` is true, keeping `rtx`, `red` and `ulpfec`.

## Recording

A [`MediaRecorder`](/guide/media/media-recording) records a sender's frames before its transform and a receiver's frames after it, so it would store an encrypted call in the clear. Whether that may happen is decided where the encryption is set up: the transforms of a key provider keep their frames from every observer of encoded frames, the recorder included, and `MediaRecorder.addTrack` refuses their senders, receivers and tracks with an `IllegalStateException`. A transform set on a track that is already being recorded keeps the recorder from any further frame of it.

An application that records its calls on purpose says so:

```java
KeyProvider keys = KeyProvider.builder(myId)
        .allowPlaintextRecording(true)
        .build();
```

A recording made this way is decrypted media on the disk of one participant, outside the protection of the call. Other transforms decide for themselves through `RTCEncodedFrameTransformer.allowsPlaintextObservers()`, which is true unless they say otherwise.

## Browsers

Browsers have no built-in SFrame support yet, but they run the same format with [`RTCRtpScriptTransform`](https://www.w3.org/TR/webrtc-encoded-transform/) and WebCrypto. [`webrtc-java-sframe.js`](/e2ee/webrtc-java-sframe.js) implements it, with a `KeyProvider` that works like the Java one. webrtc-java's tests run it in Node.js against the RFC's test vectors, and check that frames of every codec layout and cipher suite encrypted on either side decrypt on the other.

The transforms run in a worker:

```js
// worker.js
import { KeyProvider, attach } from './webrtc-java-sframe.js';

const keys = new KeyProvider({ participant: 2 });

onmessage = ({ data }) => {
    if (data.encryptionKey) {
        keys.setEncryptionKey(data.generation, data.encryptionKey);
    }
    else {
        keys.setDecryptionKey(data.participant, data.generation, data.decryptionKey);
    }
};

onrtctransform = ({ transformer }) => attach(keys, transformer);
```

and the page sets them on its senders and receivers, with `side` telling which is which:

```js
const worker = new Worker('worker.js', { type: 'module' });

worker.postMessage({ generation: 0, encryptionKey: myKey });
sender.transform = new RTCRtpScriptTransform(worker, { side: 'send' });
receiver.transform = new RTCRtpScriptTransform(worker, { side: 'receive' });
```

`attach` asks for key frames through the transformer as the Java side does. Browsers that predate `RTCRtpScriptTransform` need the older `createEncodedStreams()` instead, whose frames the encryptor's and decryptor's `transformFrame(frame, controller)` take just the same.

## Frame Format

This section specifies the format, so that it can be implemented elsewhere. It is RFC 9605 applied per frame, with the key IDs and the codec layouts below.

### Version

This is version 1, named `webrtc-java-sframe/1`: `KeyProvider.FORMAT` in Java, `FORMAT` in the browser module. Nothing in a frame says which format it is in, so the participants of a call agree on it, as on the cipher suite and the keys: by the application's signaling, as part of what it sends with the keys. A later version, such as SFrame with the RTP payload format WebRTC is working on, which needs no codec layouts, has a name of its own and is agreed the same way, never told apart by trying.

### Cipher Suites and Keys

Every participant of a call uses the same cipher suite of RFC 9605, Section 4.5, by default `AES_128_GCM_SHA256_128` (0x0004). The key and salt of each key ID are derived from the participant's key with `derive_key_salt` (RFC 9605, Section 4.4.2), and frames are encrypted and decrypted with `encrypt` and `decrypt` (Sections 4.4.3 and 4.4.4).

### Key IDs

A key ID (KID) is made up of four fields, from the most significant bit down. The lowest 16 bits are the "key generation | ratchet step" of the sender keys scheme of RFC 9605, Section 5.1, and the fields above them follow the MLS scheme of Section 5.2:

```
 63          48 47                         16 15          8 7            0
+--------------+-----------------------------+-------------+--------------+
| context (16) | participant (32)            | generation  | ratchet step |
+--------------+-----------------------------+-------------+--------------+
```

- **participant**: the ID of the participant who encrypted the frame. A receiver finds the key by it and by the generation.
- **generation**: the generation of that participant's key, 0 to 255.
- **ratchet step**: how many times the generation's key was moved forward with the ratchet of RFC 9605, Section 5.1, `base_key[i+1] = HKDF-Expand(HKDF-Extract("", base_key[i]), "SFrame 1.0 Ratchet", Nh)`. This version of the format always sends 0, and a receiver drops a frame with any other step as one whose key it does not have.
- **context**: one per RTP stream the participant sends, given out from 0 upwards and never reused for the participant's key provider, which therefore serves up to 65,536 streams. Since the KID goes into the key derivation, every stream encrypts with a key and salt of its own.

### Counters

Each stream counts its frames from 0, one counter value (CTR) per frame, and never resets the counter, not even when the key changes. A receiver accepts a counter at most once per KID, and only if it is at most the replay window below the highest counter it accepted. It marks a counter as seen only after the frame authenticated.

### Frame Layout

A protected frame is the codec's clear prefix followed by an SFrame ciphertext, the header, the ciphertext and the tag:

```
+--------------+---------------+------------------------+-----+
| clear prefix | SFrame header | encrypted frame data   | tag |
+--------------+---------------+------------------------+-----+
```

The clear prefix is also the `metadata` that SFrame authenticates along with its header, and the plaintext is the rest of the frame:

| Codec (MIME type) | Clear prefix |
| --- | --- |
| `video/VP8` | the first 10 bytes of a key frame, the first 3 of a delta frame |
| `video/VP9` | none |
| `video/AV1` | one OBU header: `0x08` (OBU_SEQUENCE_HEADER) for a key frame, `0x30` (OBU_FRAME) otherwise; the plaintext is the whole frame |
| `audio/opus` | the first byte |
| any other `audio/*` | none |
| anything else | not covered: the frame is dropped |

An AV1 frame arrives with its OBU header changed by the depacketizer, which sets the size bit (`0x02`) and inserts the OBU's size as a LEB128 value. A receiver accepts the OBU header with or without the size bit, skips the size, and authenticates the header without the size bit: `0x08` or `0x30`. It takes a VP8 frame for a key frame if its first byte has the lowest bit clear, as the depacketizer does.

### Receiving

A receiver drops a frame, rather than passing it to the decoder, when its codec is not covered, it is shorter than its clear prefix, its header is not in the minimal encoding of RFC 9605, Section 4.3, it has no key for the KID's participant and generation, its counter fails the replay check, or it does not authenticate.

## Security Considerations

- SFrame protects the media, not its metadata. The servers still see who sends, at what rate and with which key IDs, which tell the participant IDs and key generations, and they see the clear prefixes: whether a VP8 frame is a key frame and its size, and the Opus TOC byte.
- Every participant who has a key can encrypt frames that pass for that key's participant, since SFrame keys are symmetric (RFC 9605, Section 7.2). Telling participants apart is up to the key distribution.
- Short tags (the `AES_128_CTR_HMAC_SHA256_*` suites) make forgeries more likely; keep the replay window on with them.
- The keys are held in Java memory for as long as the key provider has them.

## Related

- [Encoded Transforms](/guide/advanced/encoded-transforms)
- [Media Recording](/guide/media/media-recording)
- [Client API](/guide/client-api)
- [RFC 9605: Secure Frame (SFrame)](https://www.rfc-editor.org/rfc/rfc9605)
