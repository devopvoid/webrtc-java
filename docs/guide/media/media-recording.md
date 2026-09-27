# Media Recording

This guide explains how to record what a peer connection sends or receives into a media file with `MediaRecorder`, from the `webrtc-java-media` module. It covers:

- Recording senders and receivers
- Choosing the container
- Following a recording with a listener
- How tracks are started, timed and left out
- Recording an end-to-end encrypted call

`MediaRecorder` writes the encoded frames of each sender or receiver into the file as they are, without decoding or re-encoding them. A recording therefore costs next to no CPU, keeps exactly the quality that went over the network, and needs no codec at all, only the container. The frames are taken in native code, straight from WebRTC, and written by a thread of the recorder's own, so neither Java nor a slow disk ever holds up the call.

See [Media Files](/guide/media/media-files) for how to add the module to a project.

## Recording a Call

Add the senders and receivers to record, then start:

```java
try (MediaRecorder recorder = new MediaRecorder(Paths.get("call.mkv"))) {
    recorder.addTrack(videoReceiver);
    recorder.addTrack(audioReceiver);
    recorder.start();

    // ... the call goes on ...

    recorder.stop();
}
```

A sender's frames are recorded as they leave the encoder, a receiver's as they go to the decoder. Senders and receivers of any number of peer connections can go into one file, e.g. both sides of a call.

`stop()` waits until everything received so far is written and the file is finished, and returns whether the file holds a recording. A recorder that never got any media to write deletes its file rather than leave an unplayable one behind. `close()` stops a recording that still runs.

::: warning
Keep the senders and receivers undisposed while recording. The recorder asks them for key frames, and a disposed one cannot be asked.
:::

## Containers

The container follows the file name:

| Extension | Video | Audio | Notes |
| --- | --- | --- | --- |
| `.mkv` | VP8, VP9, AV1, H.264, H.265 | Opus, G.711 | Holds every codec WebRTC sends; the safe choice. |
| `.webm` | VP8, VP9, AV1 | Opus | Plays in browsers. |
| `.mp4` | VP9, AV1, H.264, H.265 | Opus | Written fragmented, so it plays up to where a recording was cut short. |

A name FFmpeg does not know gets Matroska. A track whose codec the file cannot hold, VP8 in MP4 for example, is left out and reported as a warning, and the rest is recorded.

## Following a Recording

A `MediaRecorderListener` hears when the file begins, what was left out, and whether writing failed:

```java
recorder.setListener(new MediaRecorderListener() {

    @Override
    public void onStarted() {
        System.out.println("Recording");
    }

    @Override
    public void onWarning(String message) {
        System.out.println("Warning: " + message);
    }

    @Override
    public void onError(String message) {
        System.out.println("Failed: " + message);
    }
});
```

Calls arrive in order on a thread the recorder keeps for them, so a listener may take its time and may even stop the recorder.

## How Tracks Are Recorded

- **Video starts at a key frame.** A decoder can do nothing with the frames before one, so they are skipped. The recorder asks the sender or receiver for a key frame, so recording starts within moments instead of waiting until WebRTC sends one of its own accord, which it rarely does.
- **The file begins once the tracks are known.** The file header describes every stream, and a stream is only known from its first frames. The file begins once every track has sent some, or once the tracks that have waited three seconds for the rest. A track that sends nothing by then is left out, with a warning.
- **Tracks share one timeline.** Each track is placed by when its first frame arrived, and follows its own RTP timestamps from there, so its timing is exact. The tracks of one sender line up within the jitter of the network.
- **One simulcast layer.** A sender with simulcast is recorded at the layer whose frames reach the recorder first.
- **Nothing is lost to a slow disk, up to a point.** Frames queue up in memory for the writer, up to 64 MB. Beyond that, frames are dropped, and video starts over at the next key frame.

## Recording an Encrypted Call

A sender's frames are recorded before an [encoded transform](/guide/advanced/encoded-transforms) runs on them, and a receiver's after it. An end-to-end encrypted call is therefore recorded in the clear, on either side. [`EncryptedRecordingExample`](https://github.com/devopvoid/webrtc-java/blob/master/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/EncryptedRecordingExample.java) shows both together.
