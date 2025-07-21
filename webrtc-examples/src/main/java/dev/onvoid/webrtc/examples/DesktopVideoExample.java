/*
 * Copyright 2025 WebRTC Java Contributors
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
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;

/**
 * Example demonstrating how to set up a peer connection with a desktop video source.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Create a PeerConnectionFactory</li>
 *   <li>Get available desktop sources (screens and windows)</li>
 *   <li>Create a VideoDesktopSource for capturing screen or window content</li>
 *   <li>Configure the VideoDesktopSource properties</li>
 *   <li>Create a video track with the desktop source</li>
 *   <li>Set up a peer connection</li>
 * </ul>
 * <p>
 * Note: This example focuses only on setting up the local peer connection with
 * a desktop video source for bidirectional media transfer. In a real application,
 * you would need to establish a connection with a remote peer through a signaling
 * channel (e.g., WebSocket).
 *
 * @author Alex Andres
 */
public class DesktopVideoExample {

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
            Logger.getLogger(DesktopVideoExample.class.getName())
                    .log(Level.SEVERE, "Error in DesktopVideoExample", e);
        }
        finally {
            // Dispose the factory when done.
            factory.dispose();
        }
    }

    /**
     * Represents a peer connection with audio and desktop video tracks.
     */
    private static class LocalPeer implements PeerConnectionObserver {

        private final RTCPeerConnection peerConnection;
        private final VideoDesktopSource videoSource;


        public LocalPeer(PeerConnectionFactory factory) {
            // Create a basic configuration for the peer connection.
            RTCConfiguration config = new RTCConfiguration();

            // Add a STUN server to help with NAT traversal.
            RTCIceServer iceServer = new RTCIceServer();
            iceServer.urls.add("stun:stun.l.google.com:19302");
            config.iceServers.add(iceServer);

            // Create the peer connection.
            peerConnection = factory.createPeerConnection(config, this);

            // Get available desktop sources.
            System.out.println("Getting available desktop sources...");

            // Get available screens.
            ScreenCapturer screenCapturer = new ScreenCapturer();
            List<DesktopSource> screens = screenCapturer.getDesktopSources();
            System.out.println("\nAvailable screens:");
            for (DesktopSource screen : screens) {
                System.out.printf("  Screen: %s (ID: %d)%n", screen.title, screen.id);
            }

            // Get available windows.
            WindowCapturer windowCapturer = new WindowCapturer();
            List<DesktopSource> windows = windowCapturer.getDesktopSources();
            System.out.println("\nAvailable windows:");
            for (DesktopSource window : windows) {
                System.out.printf("  Window: %s (ID: %d)%n", window.title, window.id);
            }

            // Clean up the capturers as we only needed them to get the sources.
            screenCapturer.dispose();
            windowCapturer.dispose();

            // Create a desktop video source.
            videoSource = new VideoDesktopSource();

            // Configure the desktop video source.
            // Set frame rate (e.g., 30 fps).
            videoSource.setFrameRate(30);

            // Set maximum frame size (e.g., 1920x1080).
            videoSource.setMaxFrameSize(1920, 1080);

            // Select a source to capture.
            // For this example; we'll use the first available screen if there is one.
            if (!screens.isEmpty()) {
                DesktopSource selectedScreen = screens.get(0);
                System.out.printf("%nSelected screen for capture: %s (ID: %d)%n",
                        selectedScreen.title, selectedScreen.id);
                videoSource.setSourceId(selectedScreen.id, false);
            }
            // Otherwise, use the first available window if there is one.
            else if (!windows.isEmpty()) {
                DesktopSource selectedWindow = windows.get(0);
                System.out.printf("%nSelected window for capture: %s (ID: %d)%n",
                        selectedWindow.title, selectedWindow.id);
                videoSource.setSourceId(selectedWindow.id, true);
            }
            // If no sources are available, fall back to a default (primary screen).
            else {
                System.out.println("\nNo desktop sources found. Using default (primary screen).");
                videoSource.setSourceId(0, false);
            }

            // Start capturing.
            videoSource.start();

            // Create a video track with the desktop source.
            VideoTrack videoTrack = factory.createVideoTrack("video0", videoSource);

            // Add the tracks to the peer connection.
            List<String> streamIds = new ArrayList<>();
            streamIds.add("stream1");
            peerConnection.addTrack(videoTrack, streamIds);

            System.out.println("LocalPeer: Created with a desktop video track");
        }

        /**
         * Closes the peer connection and releases resources.
         */
        public void dispose() {
            if (videoSource != null) {
                // Stop capturing before disposing.
                videoSource.stop();
                videoSource.dispose();
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

            System.out.println("LocalPeer: Transceiver track added: " + track.getKind());
        }
    }
}