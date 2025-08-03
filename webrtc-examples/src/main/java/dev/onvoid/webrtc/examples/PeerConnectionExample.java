/*
 * Copyright 2025 Alex Andres
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
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

/**
 * Example demonstrating how to set up a peer connection with audio and video tracks
 * to be able to send and receive media.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Create a PeerConnectionFactory</li>
 *   <li>Create audio and video tracks</li>
 *   <li>Set up a peer connection</li>
 *   <li>Add tracks to the peer connection for sending media</li>
 *   <li>Implement callbacks to receive incoming audio and video frames</li>
 * </ul>
 * <p>
 * Note: This example focuses only on setting up the local peer connection with
 * audio and video tracks for bidirectional media transfer. In a real application,
 * you would need to establish a connection with a remote peer through a signaling
 * channel (e.g., WebSocket).
 *
 * @author Alex Andres
 */
public class PeerConnectionExample {

    public static void main(String[] args) {
        // Create a PeerConnectionFactory, which is the main entry point for WebRTC.
        PeerConnectionFactory factory = new PeerConnectionFactory();

        try {
            LocalPeer localPeer = new LocalPeer(factory);

            // Keep the application running to observe state changes.
            System.out.println("Press Enter to exit...");
            System.in.read();

            // Clean up.
            localPeer.dispose();
        }
        catch (Exception e) {
            Logger.getLogger(PeerConnectionExample.class.getName())
                    .log(Level.SEVERE, "Error in PeerConnectionExample", e);
        }
        finally {
            // Dispose the factory when done
            factory.dispose();
        }
    }

    /**
     * Represents a peer connection with audio and video tracks.
     */
    private static class LocalPeer implements PeerConnectionObserver {

        private final RTCPeerConnection peerConnection;
        private final AudioTrack audioTrack;
        private final VideoTrack videoTrack;
        private final AudioFrameLogger audioFrameLogger = new AudioFrameLogger();
        private final VideoFrameLogger videoFrameLogger = new VideoFrameLogger();


        public LocalPeer(PeerConnectionFactory factory) {
            // Create a basic configuration for the peer connection.
            RTCConfiguration config = new RTCConfiguration();

            // Add a STUN server to help with NAT traversal.
            RTCIceServer iceServer = new RTCIceServer();
            iceServer.urls.add("stun:stun.l.google.com:19302");
            config.iceServers.add(iceServer);

            // Create the peer connection.
            peerConnection = factory.createPeerConnection(config, this);

            // Create an audio source with options.
            AudioOptions audioOptions = new AudioOptions();
            audioOptions.echoCancellation = true;
            audioOptions.autoGainControl = true;
            audioOptions.noiseSuppression = true;

            AudioTrackSource audioSource = factory.createAudioSource(audioOptions);
            audioTrack = factory.createAudioTrack("audio0", audioSource);

            VideoDeviceSource videoSource = new VideoDeviceSource();
            videoTrack = factory.createVideoTrack("video0", videoSource);

            // Add the tracks to the peer connection.
            List<String> streamIds = new ArrayList<>();
            streamIds.add("stream1");
            peerConnection.addTrack(audioTrack, streamIds);
            peerConnection.addTrack(videoTrack, streamIds);

            System.out.println("LocalPeer: Created with audio and video tracks");
        }

        /**
         * Closes the peer connection and releases resources.
         */
        public void dispose() {
            if (audioTrack != null) {
                audioTrack.removeSink(audioFrameLogger);
            }
            if (videoTrack != null) {
                videoTrack.removeSink(videoFrameLogger);
            }
            if (peerConnection != null) {
                peerConnection.close();
            }
        }

        // PeerConnectionObserver implementation.

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            System.out.println("LocalPeer: New ICE candidate: " + candidate.sdp);
            // In a real application, you would send this candidate to the remote peer
            // through your signaling channel.
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            System.out.println("LocalPeer: Connection state changed to: " + state);
        }

        @Override
        public void onIceConnectionChange(RTCIceConnectionState state) {
            System.out.println("LocalPeer: ICE connection state changed to: " + state);
        }

        @Override
        public void onIceGatheringChange(RTCIceGatheringState state) {
            System.out.println("LocalPeer: ICE gathering state changed to: " + state);
        }

        @Override
        public void onSignalingChange(RTCSignalingState state) {
            System.out.println("LocalPeer: Signaling state changed to: " + state);
        }

        @Override
        public void onDataChannel(RTCDataChannel dataChannel) {
            System.out.println("LocalPeer: Data channel created: " + dataChannel.getLabel());
        }

        @Override
        public void onRenegotiationNeeded() {
            System.out.println("LocalPeer: Renegotiation needed");
            // In a real application, you would create an offer and set it as the local description.
        }

        @Override
        public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
            System.out.println("LocalPeer: Track added: " + receiver.getTrack().getKind());
        }

        @Override
        public void onRemoveTrack(RTCRtpReceiver receiver) {
            System.out.println("LocalPeer: Track removed: " + receiver.getTrack().getKind());
        }

        @Override
        public void onTrack(RTCRtpTransceiver transceiver) {
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            String kind = track.getKind();

            if (kind.equals(MediaStreamTrack.AUDIO_TRACK_KIND)) {
                AudioTrack audioTrack = (AudioTrack) track;
                audioTrack.addSink(audioFrameLogger);
            }
            if (kind.equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
                VideoTrack videoTrack = (VideoTrack) track;
                videoTrack.addSink(videoFrameLogger);
            }

            System.out.println("LocalPeer: Transceiver track added: " + kind);
        }
    }



    /**
     * A simple implementation of VideoTrackSink that logs information about received frames.
     */
    private static class VideoFrameLogger implements VideoTrackSink {

        private static final long LOG_INTERVAL_MS = 1000; // Log every second
        private int frameCount = 0;
        private long lastLogTime = System.currentTimeMillis();


        @Override
        public void onVideoFrame(VideoFrame frame) {
            frameCount++;

            long now = System.currentTimeMillis();
            if (now - lastLogTime >= LOG_INTERVAL_MS) {
                System.out.printf("Received %d video frames in the last %.1f seconds%n",
                        frameCount, (now - lastLogTime) / 1000.0);
                System.out.printf("Last frame: %dx%d, rotation: %d, timestamp: %dms%n",
                        frame.buffer.getWidth(), frame.buffer.getHeight(), frame.rotation,
                        frame.timestampNs / 1000000);

                frameCount = 0;
                lastLogTime = now;
            }

            // Release the native resources associated with this frame to prevent memory leaks.
            frame.release();
        }
    }



    /**
     * A simple implementation of AudioTrackSink that logs information about received audio data.
     */
    private static class AudioFrameLogger implements AudioTrackSink {

        private static final long LOG_INTERVAL_MS = 1000; // Log every second
        private int frameCount = 0;
        private long lastLogTime = System.currentTimeMillis();


        @Override
        public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
            frameCount++;

            long now = System.currentTimeMillis();
            if (now - lastLogTime >= LOG_INTERVAL_MS) {
                System.out.printf("Received %d audio frames in the last %.1f seconds%n",
                        frameCount, (now - lastLogTime) / 1000.0);
                System.out.printf("Last audio data: %d bytes, %d bits/sample, %d Hz, %d channels, %d frames%n",
                        data.length, bitsPerSample, sampleRate, channels, frames);

                frameCount = 0;
                lastLogTime = now;
            }
        }
    }
}