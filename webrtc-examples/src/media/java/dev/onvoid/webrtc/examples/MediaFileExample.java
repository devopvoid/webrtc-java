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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.ffmpeg.MediaFileSource;
import dev.onvoid.webrtc.media.ffmpeg.MediaInfo;
import dev.onvoid.webrtc.media.ffmpeg.MediaPlayerListener;
import dev.onvoid.webrtc.media.ffmpeg.MediaPlayerState;
import dev.onvoid.webrtc.media.video.VideoTrack;

import java.util.List;

/**
 * Example demonstrating how to send a media file over a peer connection, in
 * place of a camera and a microphone.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Open a media file or network stream with a MediaFileSource</li>
 *   <li>Read what the source contains from its MediaInfo</li>
 *   <li>Create audio and video tracks from the sources it feeds</li>
 *   <li>Add those tracks to a peer connection</li>
 *   <li>Follow playback through a MediaPlayerListener</li>
 * </ul>
 * <p>
 * Decoding happens entirely in native code. Frames are paced in real time and
 * carry the presentation times of the file, so what a receiver gets keeps the
 * timing of the media rather than the timing of a Java thread.
 * <p>
 * Note: this example only sets up the local side. A real application would
 * reach a remote peer through a signaling channel, as
 * {@link PeerConnectionExample} shows.
 * <p>
 * Run it with the media file to send:
 * <pre>
 * java dev.onvoid.webrtc.examples.MediaFileExample movie.mp4
 * </pre>
 * <p>
 * Only local files play today. FFmpeg demuxes network sources just as well,
 * so the same code covers http, rtsp and rtmp once those protocols are turned
 * on in the build.
 *
 * @author Alex Andres
 */
public class MediaFileExample {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: MediaFileExample <media-file>");
            System.out.println("  for example: MediaFileExample movie.mp4");
            return;
        }

        // A factory that sends pushed audio must not also be capturing from a
        // microphone, so this one is given a dummy audio layer. An application
        // that needs both needs a second factory.
        AudioDeviceModule audioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        PeerConnectionFactory factory = new PeerConnectionFactory(audioModule);

        // Opening reads the container and picks the streams to play. A source
        // with nothing playable in it fails here.
        try (MediaFileSource source = new MediaFileSource(args[0])) {
            printInfo(source.getInfo());

            LocalPeer localPeer = new LocalPeer(factory, source);

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
                System.out.printf("  at %.1f s, %d video frames and %d audio chunks sent%n",
                        source.getPositionUs() / 1_000_000.0,
                        localPeer.videoFrames.get(), localPeer.audioChunks.get());
            }

            // The tracks go before the source, which disposes of the media
            // sources they were made from.
            localPeer.dispose();
        }
        catch (Exception e) {
            Logger.getLogger(MediaFileExample.class.getName())
                    .log(Level.SEVERE, "Error in MediaFileExample", e);
        }
        finally {
            factory.dispose();
            audioModule.dispose();
        }
    }

    private static void printInfo(MediaInfo info) {
        System.out.printf("Source runs %.3f s%n", info.getDurationUs() / 1_000_000.0);

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

    /**
     * A peer connection carrying the tracks a media file feeds.
     */
    private static class LocalPeer implements PeerConnectionObserver {

        final AtomicInteger videoFrames = new AtomicInteger();
        final AtomicInteger audioChunks = new AtomicInteger();

        private final RTCPeerConnection peerConnection;
        private final VideoTrack videoTrack;
        private final AudioTrack audioTrack;
        private final RTCRtpSender videoSender;
        private final RTCRtpSender audioSender;


        LocalPeer(PeerConnectionFactory factory, MediaFileSource source) {
            peerConnection = factory.createPeerConnection(new RTCConfiguration(), this);

            // A source with no video has no video source, and likewise for
            // audio, so each track is only made if there is something to feed
            // it.
            if (source.getVideoSource() != null) {
                videoTrack = factory.createVideoTrack("video", source.getVideoSource());

                // Watching the track shows what the peer connection is being
                // given. A real application would not need this.
                videoTrack.addSink(frame -> videoFrames.incrementAndGet());

                videoSender = peerConnection.addTrack(videoTrack, List.of("stream"));
            }
            else {
                videoTrack = null;
                videoSender = null;
            }

            if (source.getAudioSource() != null) {
                audioTrack = factory.createAudioTrack("audio", source.getAudioSource());
                audioTrack.addSink((data, bits, rate, channels, frames) ->
                        audioChunks.incrementAndGet());

                audioSender = peerConnection.addTrack(audioTrack, List.of("stream"));
            }
            else {
                audioTrack = null;
                audioSender = null;
            }
        }

        void dispose() {
            // An RTCRtpSender is not owned by the peer connection, so each one
            // handed out by addTrack has to be released here. Doing it before
            // the connection closes is what lets go of the tracks, and with
            // them the media sources the file source owns.
            if (videoSender != null) {
                videoSender.dispose();
            }
            if (audioSender != null) {
                audioSender.dispose();
            }

            peerConnection.close();
        }

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            // A real application sends this to the remote peer over its
            // signaling channel.
        }

        @Override
        public void onIceConnectionChange(RTCIceConnectionState state) {
            System.out.println("ICE connection state: " + state);
        }
    }
}
