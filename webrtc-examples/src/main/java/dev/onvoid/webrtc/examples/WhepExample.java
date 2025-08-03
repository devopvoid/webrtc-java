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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.video.VideoDeviceSource;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * Example implementation of WebRTC HTTP Egress Protocol (WHEP) client.
 * <p>
 * This class demonstrates:
 * <ul>
 *   <li>Setting up a WebRTC peer connection</li>
 *   <li>Creating and sending an SDP offer to a WHEP endpoint</li>
 *   <li>Receiving and processing an SDP answer</li>
 *   <li>Establishing media streaming over WebRTC</li>
 * </ul>
 * <p>
 * The example creates a receive-only peer connection that can accept
 * incoming video streams from a WHEP-compatible server.
 *
 * @see <a href="https://www.ietf.org/archive/id/draft-murillo-whep-03.html">WHEP Specification</a>
 * 
 * @author Alex Andres
 */
public class WhepExample {

    private static final String WHEP_ENDPOINT_URL = "http://localhost:8889/mystream/whep";

    /** Factory for creating peer connections and media objects. */
    private PeerConnectionFactory factory;

    /** The WebRTC peer connection that handles media communication. */
    private RTCPeerConnection peerConnection;

    /** The local SDP offer to be sent to the remote endpoint. */
    private RTCSessionDescription localOffer;

    // Synchronization objects for async operations.
    private final CountDownLatch offerCreatedLatch = new CountDownLatch(1);
    private final CountDownLatch localDescriptionSetLatch = new CountDownLatch(1);
    private final CountDownLatch remoteDescriptionSetLatch = new CountDownLatch(1);


    public static void main(String[] args) {
        WhepExample example = new WhepExample();

        try {
            example.run();
        }
        catch (Exception e) {
            Logger.getLogger("WHEPExample").log(Level.SEVERE, "Error running WHEP example", e);
        }
        finally {
            example.cleanup();
        }
    }

    public void run() throws Exception {
        System.out.println("Starting WebRTC Peer Connection Example");

        initializePeerConnectionFactory();
        createPeerConnection();
        createOffer();

        // Wait for the offer to be created.
        if (!offerCreatedLatch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timeout waiting for offer creation.");
        }

        // Set the local description (the offer).
        setLocalDescription(localOffer);

        // Wait for the local description to be set.
        if (!localDescriptionSetLatch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timeout waiting for local description to be set.");
        }

        System.out.println("Local offer created and set.");
        //System.out.println("SDP Offer: " + localOffer.sdp);
        System.out.println("Sending local offer to the remote endpoint.");

        String answerSdp = sendOfferEndpoint(localOffer.sdp);

        //System.out.println("SDP Answer: " + answerSdp);

        // Set the remote description (the answer).
        setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, answerSdp));

        // Wait for the remote description to be set.
        if (!remoteDescriptionSetLatch.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timeout waiting for remote description to be set.");
        }

        System.out.println("Remote answer set. Peer connection established!");
        System.out.println("Media should now be exchanged between peers.");

        // Wait a bit to see connection state changes.
        Thread.sleep(10000);

        System.out.println("WebRTC Peer Connection Example completed.");
    }

    private void initializePeerConnectionFactory() {
        System.out.println("Initializing PeerConnectionFactory.");
        factory = new PeerConnectionFactory();
    }

    private void createPeerConnection() {
        System.out.println("Creating peer connection.");

        // Create ICE servers configuration.
        RTCConfiguration config = new RTCConfiguration();

        // Add Google's public STUN server.
        RTCIceServer iceServer = new RTCIceServer();
        iceServer.urls.add("stun:stun.l.google.com:19302");
        config.iceServers.add(iceServer);

        // Create the peer connection with our observer.
        peerConnection = factory.createPeerConnection(config, new PeerConnectionObserverImpl());

        // Create a video track from a video device source (e.g., webcam).
        // Since we are only receiving video in this example, the source will be a dummy video source.
        VideoDeviceSource videoSource = new VideoDeviceSource();
        VideoTrack videoTrack = factory.createVideoTrack("videoTrack", videoSource);
        videoTrack.addSink(videoFrame -> System.out.println("Received video frame: " + videoFrame));

        // Only interested in receiving video, so we set up a transceiver for that.
        RTCRtpTransceiverInit transceiverInit = new RTCRtpTransceiverInit();
        transceiverInit.direction = RTCRtpTransceiverDirection.RECV_ONLY;

        // Add the transceiver to the peer connection with the video track.
        RTCRtpTransceiver transceiver = peerConnection.addTransceiver(videoTrack, transceiverInit);

        // Set up a sink to handle incoming video frames.
        MediaStreamTrack track = transceiver.getReceiver().getTrack();
        if (track instanceof VideoTrack vTrack) {
            vTrack.addSink(videoFrame -> {
                System.out.println("Received video frame: " + videoFrame);
            });
        }
    }

    private void createOffer() {
        System.out.println("Creating offer.");

        // Create offer options (use default options).
        RTCOfferOptions options = new RTCOfferOptions();

        // Create the offer.
        peerConnection.createOffer(options, new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                System.out.println("Offer created successfully.");
                localOffer = description;
                offerCreatedLatch.countDown();
            }

            @Override
            public void onFailure(String error) {
                System.err.println("Failed to create offer: " + error);
                offerCreatedLatch.countDown();
            }
        });
    }

    private void setLocalDescription(RTCSessionDescription description) {
        System.out.println("Setting local description.");
        
        peerConnection.setLocalDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                System.out.println("Local description set successfully.");
                localDescriptionSetLatch.countDown();
            }
            
            @Override
            public void onFailure(String error) {
                System.err.println("Failed to set local description: " + error);
                localDescriptionSetLatch.countDown();
            }
        });
    }

    private String sendOfferEndpoint(String sdpOffer) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WHEP_ENDPOINT_URL))
                .header("Content-Type", "application/sdp")
                .POST(HttpRequest.BodyPublishers.ofString(sdpOffer))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200 || response.statusCode() == 201) {
            System.out.println("WHEP request successful");
            return response.body();
        }
        else {
            throw new RuntimeException("WHEP request failed with status: " + response.statusCode());
        }
    }

    private void setRemoteDescription(RTCSessionDescription description) {
        System.out.println("Setting remote description.");

        peerConnection.setRemoteDescription(description, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                System.out.println("Remote description set successfully.");
                remoteDescriptionSetLatch.countDown();
            }

            @Override
            public void onFailure(String error) {
                System.err.println("Failed to set remote description: " + error);
                remoteDescriptionSetLatch.countDown();
            }
        });
    }

    private void cleanup() {
        System.out.println("Cleaning up resources.");

        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        if (factory != null) {
            factory.dispose();
            factory = null;
        }
    }



    /**
     * Implementation of PeerConnectionObserver to handle events from the peer connection.
     */
    private static class PeerConnectionObserverImpl implements PeerConnectionObserver {

        @Override
        public void onIceCandidate(RTCIceCandidate candidate) {
            System.out.println("ICE candidate: " + candidate.sdp);
            // In a real application, we would send this candidate to the remote peer
        }

        @Override
        public void onConnectionChange(RTCPeerConnectionState state) {
            System.out.println("Connection state changed: " + state);
        }

        @Override
        public void onIceConnectionChange(RTCIceConnectionState state) {
            System.out.println("ICE connection state changed: " + state);
        }

        @Override
        public void onIceGatheringChange(RTCIceGatheringState state) {
            System.out.println("ICE gathering state changed: " + state);
        }

        @Override
        public void onSignalingChange(RTCSignalingState state) {
            System.out.println("Signaling state changed: " + state);
        }

        @Override
        public void onDataChannel(RTCDataChannel dataChannel) {
            System.out.println("Data channel created: " + dataChannel.getLabel());
        }

        @Override
        public void onRenegotiationNeeded() {
            System.out.println("Renegotiation needed.");
        }

        @Override
        public void onAddTrack(RTCRtpReceiver receiver, MediaStream[] mediaStreams) {
            System.out.println("Track added.");
        }
    }
}