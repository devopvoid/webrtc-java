/*
 * Copyright 2026 Alex Andres
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.onvoid.webrtc.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.LocalTrack;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.media.player.MediaFileSource;
import dev.onvoid.webrtc.media.player.MediaInfo;
import dev.onvoid.webrtc.media.player.MediaPlayerListener;
import dev.onvoid.webrtc.media.player.MediaPlayerState;

/**
 * Example demonstrating how to send a media file over a peer connection, in
 * place of a camera and a microphone, with the client API.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Open a media file, or a live RTSP stream, with a MediaFileSource</li>
 *   <li>Read what the source contains from its MediaInfo</li>
 *   <li>Send the sources it feeds as client API tracks, with {@code videoTrack} and
 *       {@code audioTrack}</li>
 *   <li>Receive them on a second peer, connected through a loopback signaling</li>
 *   <li>Follow playback through a MediaPlayerListener</li>
 *   <li>Close the tracks before the source, which owns what they were made from</li>
 * </ul>
 * <p>
 * Decoding happens entirely in native code. Frames are paced in real time and
 * carry the presentation times of the file, so what a receiver gets keeps the
 * timing of the media rather than the timing of a Java thread.
 * <p>
 * Both peers run in this process. A real application reaches its remote peer
 * through a {@link Signaling} that carries the messages over a WebSocket or
 * the like. {@link MediaFilePlayerExample} also shows the received video and
 * plays the received audio.
 * <p>
 * Run it with the media file or stream URL to send:
 * <pre>
 * java dev.onvoid.webrtc.examples.MediaFileExample movie.mp4
 * java dev.onvoid.webrtc.examples.MediaFileExample rtsp://camera.local/stream1
 * </pre>
 * A stream that cannot be reached, or stops sending, is given up on after the
 * reader's timeout, 10 seconds unless another is passed to the source.
 *
 * @author Alex Andres
 */
public class MediaFileExample {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: MediaFileExample <media-file | rtsp-url>");
            System.out.println("  for example: MediaFileExample movie.mp4");
            System.out.println("           or: MediaFileExample rtsp://camera.local/stream1");
            return;
        }

        // The file's audio is pushed, so the runtime must not capture from a
        // microphone, and a headless one needs no audio devices at all. The
        // runtime goes first: it loads the native library the source needs.
        try (WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build()) {
            // Opening reads the container and picks the streams to play. A
            // source with nothing playable in it fails here.
            try (MediaFileSource source = new MediaFileSource(args[0])) {
                printInfo(source.getInfo());

                SignalingPair signaling = Signaling.loopback();

                Peer sender = rtc.createPeer(PeerOptions.builder()
                        .name("sender")
                        .signaling(signaling.first())
                        .build());
                Peer receiver = rtc.createPeer(PeerOptions.builder()
                        .name("receiver")
                        .polite(true)
                        .signaling(signaling.second())
                        .build());

                AtomicInteger videoFrames = new AtomicInteger();
                AtomicLong audioMillis = new AtomicLong();

                receiver.onTrack(track -> {
                    System.out.println("Receiving " + track.kind());

                    if (track.isVideo()) {
                        track.asVideo().onFrame(frame -> videoFrames.incrementAndGet());
                    }
                    else {
                        track.asAudio().onAudio(chunk ->
                                audioMillis.addAndGet(chunk.frames() * 1000L / chunk.sampleRate()));
                    }
                });

                // A source with no video has no video source, and likewise
                // for audio, so each track is only made if there is something
                // to feed it. The sources stay the file source's.
                List<LocalTrack> tracks = new ArrayList<>();

                if (source.getVideoSource() != null) {
                    tracks.add(rtc.media().videoTrack("video", source.getVideoSource()));
                }
                if (source.getAudioSource() != null) {
                    tracks.add(rtc.media().audioTrack("audio", source.getAudioSource()));
                }

                // Both tracks go into one stream, so a receiver plays them in sync.
                tracks.forEach(sender::addTrack);

                // The main thread may wait; a handler must not.
                sender.whenConnected().get(15, TimeUnit.SECONDS);

                CountDownLatch finished = new CountDownLatch(1);

                source.setListener(new MediaPlayerListener() {

                    @Override
                    public void onStateChanged(MediaPlayerState state) {
                        System.out.println("Player state: " + state);
                    }

                    @Override
                    public void onEndOfStream() {
                        System.out.println("Reached the end of the source.");
                        finished.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        System.out.println("Playback failed: " + message);
                        finished.countDown();
                    }
                });

                // Playing the source over and over is one line, and is what a
                // stand-in for a camera usually wants:
                // source.setLooping(true);

                source.play();

                // Report where playback has got to while it runs.
                while (!finished.await(1, TimeUnit.SECONDS)) {
                    System.out.printf("  at %.1f s, %d video frames and %.1f s of audio received%n",
                            source.getPositionUs() / 1_000_000.0, videoFrames.get(),
                            audioMillis.get() / 1000.0);
                }

                // The tracks go before the source, which disposes of the media
                // sources they were made from. Waiting for them is what makes
                // closing the source safe.
                for (LocalTrack track : tracks) {
                    track.closeAsync().get(5, TimeUnit.SECONDS);
                }
            }
        }
        // Closing the runtime closed the peers.
    }

    private static void printInfo(MediaInfo info) {
        if (info.getDurationUs() > 0) {
            System.out.printf("Source runs %.3f s%n", info.getDurationUs() / 1_000_000.0);
        }
        else {
            // A live stream, which has no length.
            System.out.println("Source is live");
        }

        if (info.hasVideo()) {
            System.out.printf("  video: %dx%d at %.2f fps, %s%n", info.getVideoWidth(),
                    info.getVideoHeight(), info.getFrameRate(), info.getVideoCodec());
        }
        else {
            System.out.println("  video: none");
        }

        if (info.hasAudio()) {
            System.out.printf("  audio: %d Hz, %d channel(s), %s%n", info.getSampleRate(),
                    info.getChannels(), info.getAudioCodec());
        }
        else {
            System.out.println("  audio: none");
        }
    }
}
