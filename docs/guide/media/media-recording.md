# Media Recording

`MediaRecorder`, from the `webrtc-java-media` module, records what a peer connection sends or receives into a media file. See [Media Files](/guide/media/media-files) for how to add the module to a project.

The recorder writes the encoded frames of each sender or receiver into the file as they are, without decoding or re-encoding them. A recording therefore costs next to no CPU, keeps exactly the quality that went over the network, and needs no codec, only the container.

The frames are taken in native code, straight from WebRTC, and written by a thread of the recorder's own. Neither Java nor a slow disk ever holds up the call.

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

A sender's frames are recorded as they leave the encoder, a receiver's as they go to the decoder. Senders and receivers of any number of peer connections can go into one file, for example both sides of a call.

`stop()` waits until everything received so far is written and the file is finished and closed. It returns whether the recording was kept. A recorder that never got any media to write deletes what it wrote, rather than leave an unplayable file behind. `close()` stops a recording that still runs.

Once the recorder is stopped, `getResult()` tells how the recording ended, also after `close()`:

| Result | File | Meaning |
| --- | --- | --- |
| `COMPLETE` | kept | Everything was written, and the file was finished and closed. |
| `PARTIAL` | kept | Media went into the file, but then writing, finishing, closing or [replacing the existing file](#replacing-a-file) failed. What is in the file may still play. |
| `EMPTY` | deleted | No media arrived before the stop. |
| `FAILED` | deleted | Writing failed before any media went into the file. |

```java
recorder.stop();

if (recorder.getResult() == MediaRecorderResult.PARTIAL) {
    System.out.println("Incomplete recording in " + recorder.getFile());
}
```

::: warning
Keep the senders and receivers undisposed while recording. The recorder asks them for key frames, and a disposed one cannot be asked.
:::

With the [Client API](/guide/client-api), a recorder takes its senders and remote tracks directly:

```java
recorder.addTrack(videoSender);          // a Sender from peer.addTrack
recorder.addTrack(remoteTrack);          // a RemoteTrack from peer.onTrack
```

The peer keeps its native senders and receivers to itself, and asks them for key frames on its own thread. Nothing needs to be kept undisposed: removing a sender or closing the peer ends the track in the recording, and `stop()` still finishes the file.

## Replacing a File

A recorder does not touch a file that exists: its constructor throws a `FileAlreadyExistsException`. A new recording therefore cannot cost an earlier one by accident. To replace the file, ask for it:

```java
try (MediaRecorder recorder = new MediaRecorder(Paths.get("call.mkv"), true)) {
    // ...
}
```

The recording then goes into a temporary file beside it, named `.recording-<id>.mkv`. The existing file stays untouched until `stop()` has finished the recording. Only then does the recording take its place:

- in one step,
- with the permissions of the file it replaces,
- and a symbolic link stays a link, now to the new recording.

A recording that gets no media, or fails before any went into the file, leaves the existing file as it was.

If the process ends before the stop, the existing file is still there. The temporary file beside it holds what was recorded so far.

If the recording cannot take the place of the existing file when it stops, it stays in the temporary file. `onError` is told, the result is `PARTIAL`, and `getFile()` returns where the recording is.

## Containers

The container follows the file name:

| Extension | Video | Audio | Notes |
| --- | --- | --- | --- |
| `.mkv` | VP8, VP9, AV1, H.264, H.265 | Opus, G.711 | Holds every codec WebRTC sends; the safe choice. |
| `.webm` | VP8, VP9, AV1 | Opus | Plays in browsers. |
| `.mp4` | VP9, AV1, H.264, H.265 | Opus | Written fragmented, so it plays up to where a recording was cut short. |

A name FFmpeg does not know gets Matroska. A track whose codec the file cannot hold, such as VP8 in MP4, is left out and reported as a warning. The rest is recorded.

Matroska has no codec ID of its own for G.711, so it is stored as Windows audio (`A_MS/ACM`), the way FFmpeg itself stores it. Players built on FFmpeg, such as VLC and mpv, play it; some others do not.

## Recording in Segments

A recording that runs for hours, such as surveillance or a support session, can be split into segments. Each segment is a file that plays on its own, and ends at a duration or size. The recorder keeps as many segments as asked.

```java
SegmentOptions segments = SegmentOptions.builder(Paths.get("recordings", "camera-%05d.mkv"))
        .maxDuration(Duration.ofMinutes(10))        // end a segment after 10 minutes,
        .maxSize(512L * 1024 * 1024)                // or once it holds 512 MB
        .maxTotalSize(20L * 1024 * 1024 * 1024)     // keep the newest 20 GB
        .build();

try (MediaRecorder recorder = new MediaRecorder(segments)) {
    recorder.setListener(new MediaRecorderListener() {

        @Override
        public void onSegmentFinished(MediaRecorderSegment segment) {
            upload(segment.getFile());
        }
    });
    recorder.addTrack(videoReceiver);
    recorder.addTrack(audioReceiver);
    recorder.start();

    // ...
}
```

The file name of the pattern holds the segment's number as `%d`, or zero-padded as `%05d`; a literal percent sign is `%%`. The extension picks the container of all segments, as for a single file.

Numbering goes on from the highest number already in the directory, so a recorder started again continues after the last segment. A segment never empties a file that exists: its number is skipped instead.

- **Every segment begins at a key frame.** Once a segment has reached its duration or size, the recorder asks the video tracks for key frames. The next segment begins with the first that arrives, usually a fraction of a second later. A segment is therefore somewhat longer or larger than the limit. Without video, a segment ends right at the limit.
- **Several video tracks lose nothing.** Every other video track goes on in the segment before until its own key frame arrives, so neither file has a gap. A track that sends no key frame within three seconds starts in the new segment at its next key frame, and nothing of it is recorded until then.
- **Each segment starts at zero.** Its tracks are shifted by the same amount, so they stay in sync, and one segment follows the other without a gap. `MediaRecorderSegment.getStart()` tells where a segment lies in the recording.
- **A codec change starts a segment.** A file cannot change the codec of a stream. A track that changes its codec, for example after a renegotiation, therefore goes on in the next segment, which begins as soon as the new codec is known. A single file leaves such a track out from then on instead.
- **A slow disk holds nothing up.** The recorder's writer thread finishes and opens segments, while the frames queue up as always. Old segments are deleted on the listener's thread.

`onSegmentFinished` hears of every segment once it is finished and closed, the last one when the recorder stops. Right after it returns, the oldest segments beyond `maxSegments` or `maxTotalSize` are deleted. A segment to be kept for longer has to be moved or copied by then.

The newest segment is always kept. The one being written does not count toward `maxTotalSize`, so the recording takes up to that much and one segment more. `getSegments()` lists the segments that are kept, and `getFile()` returns the last one finished.

A write that fails, for example because the disk is full, ends the recording as it does for a single file. The segment it happened in is kept as `PARTIAL` if it holds media. A segment that failed before any media went into it is deleted.

## Replay Buffer

A `ReplayBuffer` keeps the last seconds of a call in memory and writes them into a file only when asked, for "save the last 30 seconds":

```java
try (ReplayBuffer buffer = new ReplayBuffer(Duration.ofSeconds(30))) {
    buffer.addTrack(videoReceiver);
    buffer.addTrack(audioReceiver);
    buffer.start();

    // ... something happens worth keeping ...

    buffer.save(Paths.get("incident.mkv"))
            .thenAccept(replay -> System.out.println("Saved " + replay.getDuration()));
}
```

`save` creates the file at once, and refuses one that exists. The buffer's own thread writes the replay, while the buffer goes on taking frames.

The future completes on the listener's thread with a `MediaRecorderSegment`. If nothing could be saved, it completes exceptionally, and the file is deleted. A buffer can save any number of replays, in any container. A track whose codec the container cannot hold is left out of that replay, with a warning.

A replay begins with a key frame of every video track: the last one before the window. A replay therefore holds at least the window, and a few seconds more. WebRTC rarely sends key frames of its own accord, so the buffer asks its video tracks for one every third of the window, at least every two seconds and at most every ten.

Besides the window, the buffer holds at most 256 MB, or what its constructor is given. Beyond that, the oldest frames go first, and a replay is shorter. A track that changes its codec is kept with the new codec from then on.

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

Calls arrive in order, on a thread the recorder keeps for them. A listener may therefore take its time, and may even stop the recorder.

`onError` also hears of a file that cannot be finished or closed when the recorder stops, for example because the last data does not fit on the disk. The recording then ends as `PARTIAL`.

Running out of native memory while writing ends the recording the same way:

- `onError` is told.
- The frames still waiting are dropped.
- The file is finished right away, so what is in it still plays.
- The recording ends as `PARTIAL`, or as `FAILED` if nothing had gone into the file yet.

## How Tracks Are Recorded

- **Video starts at a key frame.** A decoder can do nothing with the frames before one, so they are skipped. The recorder asks the sender or receiver for a key frame. Recording therefore starts within moments, instead of waiting until WebRTC sends one of its own accord, which it rarely does.
- **The file begins once the tracks are known.** The file header describes every stream, and a stream is only known from its first frames. The file begins once every track has sent some, or once the tracks have waited three seconds for the rest. A track that sends nothing by then is left out, with a warning.
- **Tracks share one timeline.** Each track is placed by when its first frame arrived, and follows its own RTP timestamps from there, so its timing is exact. The tracks of one sender line up within the jitter of the network.
- **One simulcast layer.** A sender with simulcast is recorded at the layer whose frames reach the recorder first.
- **Nothing is lost to a slow disk, up to a point.** Frames queue up in memory for the writer, up to 64 MB. Beyond that, frames are dropped, and video starts over at the next key frame. `getStats()` [tells how much that cost](#measuring-what-was-lost).

## Measuring What Was Lost

A result of `COMPLETE` means that everything the recorder took went into the file. It says nothing about frames the recorder had to drop before that. A single dropped frame can leave a video gap, since video only goes on from the next key frame.

`getStats()` tells what happened, per track, in the order the tracks were added. It can be asked at any time, also after `close()`:

```java
MediaRecorderStats stats = recorder.getStats();

for (MediaRecorderStats.Track track : stats.getTracks()) {
    if (track.getDroppedFrames() > 0) {
        System.out.println("Lost " + track.getDroppedFrames() + " frames");
    }
}

System.out.println("Queue peak: " + stats.getMaxQueuedBytes() + " bytes");
```

| Value | Meaning |
| --- | --- |
| `getDroppedQueueFull()` | Frames dropped because the writer did not keep up and its queue was full. |
| `getDroppedAllocationFailed()` | Frames dropped because there was no memory to copy them. |
| `getDroppedAwaitingKeyFrame()` | Video frames dropped on the way to the next key frame, since nothing could decode them after what was lost before. |
| `getDroppedFrames()` | All of these together. |

`getMaxQueuedBytes()` is the most that ever waited for the writer, of all tracks together. It shows how close a recording came to the limit of 64 MB.

Frames a track was never meant to record do not count, such as those before its first key frame, or those of another simulcast layer.

## Recording an Encrypted Call

A sender's frames are recorded before an [encoded transform](/guide/advanced/encoded-transforms) runs on them, and a receiver's after it. An end-to-end encrypted call would therefore be recorded in the clear, on either side. Whether it may be is up to the encryption:

- The transforms of a `KeyProvider` of [End-to-End Encryption](/guide/advanced/end-to-end-encryption) do not allow it by default. `addTrack` refuses their senders, receivers and tracks with an `IllegalStateException`. A recording that is running gets no further frame of a track once such a transform is set on it.
- A key provider built with `allowPlaintextRecording(true)` allows it, for an application that records its calls on purpose:

```java
KeyProvider keys = KeyProvider.builder(myId)
        .allowPlaintextRecording(true)
        .build();
```

- A transform of your own allows it unless its `allowsPlaintextObservers()` returns false.

A recording of an encrypted call holds the decrypted media, outside the protection of the call. [`EncryptedRecordingExample`](https://github.com/devopvoid/webrtc-java/blob/main/webrtc-examples/src/main/java/dev/onvoid/webrtc/examples/EncryptedRecordingExample.java) shows both together.
