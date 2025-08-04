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

package dev.onvoid.webrtc.examples.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.logging.LogManager;

import dev.onvoid.webrtc.examples.web.model.LeaveMessage;
import dev.onvoid.webrtc.media.MediaStreamTrack;

import dev.onvoid.webrtc.examples.web.client.SignalingManager;
import dev.onvoid.webrtc.examples.web.connection.PeerConnectionManager;
import dev.onvoid.webrtc.examples.web.media.MediaManager;
import dev.onvoid.webrtc.examples.web.model.JoinMessage;
import dev.onvoid.webrtc.examples.util.MediaFrameLogger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating how to combine WebSocket signaling with WebRTC peer connections.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Connect to a signaling server using WebSockets</li>
 *   <li>Set up media tracks</li>
 *   <li>Establish a WebRTC peer connection with audio and video</li>
 * </ul>
 *
 * @author Alex Andres
 */
public class WebClientExample {

    static {
        setupLogging();
    }

    private static final Logger LOG = LoggerFactory.getLogger(WebClientExample.class);

    /** Manages communication with the signaling server. */
    private final SignalingManager signaling;

    /** Handles WebRTC peer connection setup and management. */
    private final PeerConnectionManager peerConnectionManager;

    /** Manages audio and video tracks and devices. */
    private final MediaManager mediaManager;


    /**
     * Creates a new WebClientExample that combines WebSocket signaling with WebRTC.
     *
     * @param signalingUri The URI of the signaling server.
     * @param subprotocol  The WebSocket subprotocol to use.
     */
    public WebClientExample(URI signalingUri, String subprotocol) {
        signaling = new SignalingManager(signalingUri, subprotocol);
        signaling.setUserId("java-client");

        peerConnectionManager = new PeerConnectionManager();

        mediaManager = new MediaManager();
        mediaManager.createTracks(peerConnectionManager, true, true);

        setupCallbacks();

        LOG.info("WebClientExample created with audio and video tracks");
    }

    /**
     * Sets up callbacks between components.
     */
    private void setupCallbacks() {
        // Set up signaling callbacks.
        signaling.setOnOfferReceived(peerConnectionManager::handleOffer);
        signaling.setOnAnswerReceived(peerConnectionManager::handleAnswer);
        signaling.setOnCandidateReceived(peerConnectionManager::handleCandidate);
        signaling.setOnJoinReceived(this::handleJoin);
        signaling.setOnLeaveReceived(this::handleLeave);

        // Set up peer connection callbacks.
        peerConnectionManager.setOnLocalDescriptionCreated(signaling::sendSessionDescription);
        peerConnectionManager.setOnIceCandidateGenerated(signaling::sendIceCandidate);
        peerConnectionManager.setOnTrackReceived(this::handleTrackReceived);
    }

    /**
     * Handles a received media track.
     * 
     * @param track The received media track.
     */
    private void handleTrackReceived(MediaStreamTrack track) {
        String kind = track.getKind();
        LOG.info("Received track: {}", kind);

        if (kind.equals(MediaStreamTrack.AUDIO_TRACK_KIND)) {
            mediaManager.addAudioSink(MediaFrameLogger.createAudioLogger());
        }
        else if (kind.equals(MediaStreamTrack.VIDEO_TRACK_KIND)) {
            mediaManager.addVideoSink(MediaFrameLogger.createVideoLogger());
        }
    }

    /**
     * Connects to the signaling server.
     * 
     * @return true if the connection was successful, false otherwise.
     */
    public boolean connect() {
        return signaling.connect();
    }

    /**
     * Disconnects from the signaling server and closes the peer connection.
     */
    public void disconnect() {
        signaling.disconnect();

        if (mediaManager != null) {
            mediaManager.dispose();
        }
        if (peerConnectionManager != null) {
            peerConnectionManager.close();
        }

        LOG.info("Disconnected from signaling server and closed peer connection");
    }

    /**
     * Joins a room on the signaling server.
     * <p>
     * This method sends a join request to the signaling server for the specified room.
     *
     * @param roomName the name of the room to join on the signaling server
     */
    public void joinRoom(String roomName) {
        if (!signaling.isConnected()) {
            LOG.warn("Cannot join room: not connected to signaling server");
            return;
        }

        signaling.sendJoin(roomName);
    }

    /**
     * Initiates a call by creating and sending an offer.
     */
    public void call() {
        if (!signaling.isConnected()) {
            LOG.warn("Cannot initiate call: not connected to signaling server");
            return;
        }

        peerConnectionManager.setInitiator(true);
        peerConnectionManager.createOffer();
    }

    /**
     * Handles an incoming join message from the remote peer.
     *
     * @param message The join message.
     */
    private void handleJoin(JoinMessage message) {
        LOG.info("Received join message from peer: {}", message.getFrom());

        // Remote peer wants to join a room, we need to create an offer if we're not the initiator.
        if (!peerConnectionManager.isInitiator()) {
            peerConnectionManager.createOffer();
        }
    }

    /**
     * Handles an incoming leave message from the remote peer.
     *
     * @param message The leave message.
     */
    private void handleLeave(LeaveMessage message) {
        LOG.info("Peer {} has left the room", message.getFrom());

        // Handle peer leaving logic, such as closing tracks or updating UI.
    }

    /**
     * Entry point to demonstrate the WebClientExample.
     */
    public static void main(String[] args) {
        try {
            WebClientExample client = new WebClientExample(
                    URI.create("wss://localhost:8443/ws"),
                    "ws-signaling");

            if (client.connect()) {
                LOG.info("Connected to signaling server");

                // Join a room.
                client.joinRoom("default-room");

                // Initiate the call.
                client.call();

                // Keep the application running to observe state changes.
                System.out.println("Press Enter to exit...");
                System.in.read();
            }
            else {
                LOG.error("Failed to connect to signaling server");
            }

            // Clean up.
            client.disconnect();
        }
        catch (Exception e) {
            LOG.error("Error in WebClientExample", e);
        }
    }

    /**
     * Sets up logging for the application.
     */
    public static void setupLogging() {
        try {
            InputStream configFile = WebClientExample.class.getResourceAsStream("/resources/logging.properties");
            LogManager.getLogManager().readConfiguration(configFile);
        }
        catch (IOException e) {
            System.err.println("Could not load configuration file:");
            System.err.println(e.getMessage());
        }
    }
}