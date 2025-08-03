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

package dev.onvoid.webrtc.examples.web.client;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;

import dev.onvoid.webrtc.examples.web.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages WebSocket signaling for WebRTC connections.
 * <p>
 * This class handles the communication between peers during WebRTC connection establishment.
 * It manages sending and receiving:
 * <ul>
 *   <li>Session descriptions (offers and answers)</li>
 *   <li>ICE candidates</li>
 *   <li>Join messages for room-based signaling</li>
 * </ul>
 * <p>
 * The manager connects to a signaling server via WebSocket and translates WebRTC-related
 * objects to JSON messages that can be transmitted over the signaling channel.
 *
 * @author Alex Andres
 */
public class SignalingManager {

    private static final Logger LOG = LoggerFactory.getLogger(SignalingManager.class);

    /** The WebSocket client used to communicate with the signaling server. */
    private final WebSocketClient signaling;

    /** JSON mapper for serializing and deserializing signaling messages. */
    private final ObjectMapper jsonMapper;

    /** Callback that is triggered when an offer message is received from a remote peer. */
    private Consumer<SessionDescriptionMessage> onOfferReceived;

    /** Callback that is triggered when an answer message is received from a remote peer. */
    private Consumer<SessionDescriptionMessage> onAnswerReceived;

    /** Callback that is triggered when an ICE candidate is received from a remote peer. */
    private Consumer<IceCandidateMessage> onCandidateReceived;

    /** Callback that is triggered when a join message is received. */
    private Consumer<JoinMessage> onJoinReceived;

    /** Callback that is triggered when a leave message is received. */
    private Consumer<LeaveMessage> onLeaveReceived;

    private String userId = "java-client";
    private String remotePeerId = "web-client";


    /**
     * Creates a new SignalingManager with the specified URI and subprotocol.
     *
     * @param signalingUri The URI of the signaling server.
     * @param subprotocol The WebSocket subprotocol to use.
     */
    public SignalingManager(URI signalingUri, String subprotocol) {
        this.signaling = new WebSocketClient(signalingUri, subprotocol);
        this.jsonMapper = new ObjectMapper();

        setupSignaling();

        LOG.info("SignalingManager created");
    }

    /**
     * Connects to the signaling server.
     *
     * @return true if the connection was successful, false otherwise.
     */
    public boolean connect() {
        try {
            CountDownLatch connectLatch = new CountDownLatch(1);

            signaling.connect()
                    .thenAccept(ws -> {
                        LOG.info("Connected to signaling server");
                        connectLatch.countDown();
                    })
                    .exceptionally(e -> {
                        LOG.error("Failed to connect to signaling server", e);
                        connectLatch.countDown();
                        return null;
                    });

            // Wait for connection.
            connectLatch.await(5, TimeUnit.SECONDS);

            return signaling.isConnected();
        }
        catch (Exception e) {
            LOG.error("Error connecting to signaling server", e);
            return false;
        }
    }

    /**
     * Disconnects from the signaling server.
     */
    public void disconnect() {
        if (isConnected()) {
            signaling.disconnect(1000, "Client disconnecting");

            LOG.info("Disconnected from signaling server");
        }
    }

    /**
     * Sends a join message to the signaling server.
     *
     * @param roomId The ID of the room to join.
     */
    public void sendJoin(String roomId) {
        try {
            JoinMessage joinMessage = new JoinMessage(userId, roomId, "Java Client");

            // Convert to JSON and send.
            String joinMessageJson = jsonMapper.writeValueAsString(joinMessage);
            signaling.sendMessage(joinMessageJson);

            LOG.info("Sent join message for room: {}", roomId);
        }
        catch (Exception e) {
            LOG.error("Error creating JSON join message", e);
        }
    }

    /**
     * Sends a session description (offer or answer) to the remote peer.
     *
     * @param sdp The session description to send.
     */
    public void sendSessionDescription(RTCSessionDescription sdp) {
        String type = sdp.sdpType.toString().toLowerCase();

        try {
            SessionDescriptionMessage message = new SessionDescriptionMessage(
                    type,
                    userId,
                    remotePeerId,
                    sdp.sdp
            );

            String messageJson = jsonMapper.writeValueAsString(message);
            signaling.sendMessage(messageJson);

            LOG.info("Sent {} to remote peer", type);
        }
        catch (Exception e) {
            LOG.error("Error creating JSON message", e);
        }
    }

    /**
     * Sends an ICE candidate to the remote peer.
     *
     * @param candidate The ICE candidate to send.
     */
    public void sendIceCandidate(RTCIceCandidate candidate) {
        try {
            IceCandidateMessage message = new IceCandidateMessage(
                    userId,
                    remotePeerId,
                    candidate.sdp,
                    candidate.sdpMid,
                    candidate.sdpMLineIndex
            );

            String candidateJson = jsonMapper.writeValueAsString(message);
            signaling.sendMessage(candidateJson);

            LOG.info("Sent ICE candidate to remote peer");
        }
        catch (Exception e) {
            LOG.error("Error creating JSON message", e);
        }
    }

    /**
     * Sets up the WebSocket signaling to handle incoming messages.
     */
    private void setupSignaling() {
        signaling.addMessageListener(message -> {
            try {
                // Parse the message to extract the type.
                String type = jsonMapper.readTree(message).path("type").asText();
                MessageType messageType = MessageType.fromString(type);

                switch (messageType) {
                    case OFFER -> handleOfferMessage(message);
                    case ANSWER -> handleAnswerMessage(message);
                    case ICE_CANDIDATE -> handleIceCandidateMessage(message);
                    case JOIN -> handleJoinMessage(message);
                    case LEAVE -> handleLeaveMessage(message);
                    case HEARTBEAT_ACK -> LOG.info("Received: {}", type);
                    default -> LOG.info("Received message with unknown type: {}", type);
                }
            }
            catch (Exception e) {
                LOG.error("Error parsing message type", e);
            }
        });

        signaling.addCloseListener(() -> {
            LOG.info("Signaling connection closed");
        });

        signaling.addErrorListener(error -> {
            LOG.error("Signaling error", error);
        });
    }

    /**
     * Sets a callback to be invoked when an offer is received.
     *
     * @param callback The callback to invoke.
     */
    public void setOnOfferReceived(Consumer<SessionDescriptionMessage> callback) {
        this.onOfferReceived = callback;
    }

    /**
     * Sets a callback to be invoked when an answer is received.
     *
     * @param callback The callback to invoke.
     */
    public void setOnAnswerReceived(Consumer<SessionDescriptionMessage> callback) {
        this.onAnswerReceived = callback;
    }

    /**
     * Sets a callback to be invoked when an ICE candidate is received.
     *
     * @param callback The callback to invoke.
     */
    public void setOnCandidateReceived(Consumer<IceCandidateMessage> callback) {
        this.onCandidateReceived = callback;
    }

    /**
     * Sets a callback to be invoked when a join message is received.
     *
     * @param callback The callback to invoke.
     */
    public void setOnJoinReceived(Consumer<JoinMessage> callback) {
        this.onJoinReceived = callback;
    }

    /**
     * Sets a callback to be invoked when a leave message is received.
     *
     * @param callback The callback to invoke.
     */
    public void setOnLeaveReceived(Consumer<LeaveMessage> callback) {
        this.onLeaveReceived = callback;
    }

    /**
     * Sets the user ID for this client.
     *
     * @param userId The user ID to set.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Sets the remote peer ID.
     *
     * @param remotePeerId The remote peer ID to set.
     */
    public void setRemotePeerId(String remotePeerId) {
        this.remotePeerId = remotePeerId;
    }

    /**
     * Gets the user ID for this client.
     *
     * @return The user ID.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Gets the remote peer ID.
     *
     * @return The remote peer ID.
     */
    public String getRemotePeerId() {
        return remotePeerId;
    }

    /**
     * Checks if the signaling connection is established.
     *
     * @return true if connected, false otherwise.
     */
    public boolean isConnected() {
        return signaling != null && signaling.isConnected();
    }

    /**
     * Handles an incoming offer message.
     *
     * @param message The JSON message containing an offer.
     */
    private void handleOfferMessage(String message) {
        if (onOfferReceived != null) {
            try {
                SessionDescriptionMessage sdpMessage = jsonMapper.readValue(message, SessionDescriptionMessage.class);
                onOfferReceived.accept(sdpMessage);
            }
            catch (Exception e) {
                LOG.error("Error parsing offer message", e);
            }
        }
    }

    /**
     * Handles an incoming answer message.
     *
     * @param message The JSON message containing an answer.
     */
    private void handleAnswerMessage(String message) {
        if (onAnswerReceived != null) {
            try {
                SessionDescriptionMessage sdpMessage = jsonMapper.readValue(message, SessionDescriptionMessage.class);
                onAnswerReceived.accept(sdpMessage);
            }
            catch (Exception e) {
                LOG.error("Error parsing answer message", e);
            }
        }
    }

    /**
     * Handles an incoming ICE candidate message.
     *
     * @param message The JSON message containing an ICE candidate.
     */
    private void handleIceCandidateMessage(String message) {
        if (onCandidateReceived != null) {
            try {
                IceCandidateMessage iceMessage = jsonMapper.readValue(message, IceCandidateMessage.class);
                onCandidateReceived.accept(iceMessage);
            }
            catch (Exception e) {
                LOG.error("Error parsing ICE candidate message", e);
            }
        }
    }

    /**
     * Handles an incoming join message.
     *
     * @param message The JSON message containing join information.
     */
    private void handleJoinMessage(String message) {
        if (onJoinReceived != null) {
            try {
                JoinMessage joinMessage = jsonMapper.readValue(message, JoinMessage.class);
                onJoinReceived.accept(joinMessage);
            }
            catch (Exception e) {
                LOG.error("Error parsing join message", e);
            }
        }
    }

    /**
     * Handles an incoming leave message.
     *
     * @param message The JSON message indicating a peer has left.
     */
    private void handleLeaveMessage(String message) {
        if (onLeaveReceived != null) {
            try {
                LeaveMessage leaveMessage = jsonMapper.readValue(message, LeaveMessage.class);
                onLeaveReceived.accept(leaveMessage);
            }
            catch (Exception e) {
                LOG.error("Error parsing join message", e);
            }
        }
    }
}