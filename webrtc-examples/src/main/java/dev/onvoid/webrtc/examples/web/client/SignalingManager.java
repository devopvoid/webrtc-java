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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.client.SignalMessage;
import dev.onvoid.webrtc.client.Signaling;

import dev.onvoid.webrtc.examples.web.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carries a peer's signaling over a WebSocket, to and from the browser client.
 * <p>
 * The browser client and the server speak a message format of their own, which
 * names the sender and the receiver of each message and wraps the session
 * description or ICE candidate in a {@code data} object:
 * <pre>{@code
 * {"type":"offer","from":"java-client","to":"web-client","data":{"sdp":"v=0..."}}
 * {"type":"ice-candidate","from":"web-client","to":"java-client","data":{"candidate":"...","sdpMid":"0","sdpMLineIndex":0}}
 * }</pre>
 * This class translates between that format and the {@link SignalMessage}s of the
 * client API. As a {@link Signaling}, it sends a peer's messages and hands the
 * peer the ones the browser sends. It also sends and receives the join and leave
 * messages of the room.
 *
 * @author Alex Andres
 */
public class SignalingManager implements Signaling {

    private static final Logger LOG = LoggerFactory.getLogger(SignalingManager.class);

    /** The WebSocket client used to communicate with the signaling server. */
    private final WebSocketClient signaling;

    /** JSON mapper for serializing and deserializing signaling messages. */
    private final ObjectMapper jsonMapper;

    /** The peer that receives the session descriptions and candidates of the browser. */
    private volatile Consumer<SignalMessage> inbound;

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
     * @param subprotocol  The WebSocket subprotocol to use.
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
            signaling.connect().get(5, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            LOG.error("Failed to connect to signaling server", e);
        }

        return signaling.isConnected();
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
     * Sends a join message to the signaling server to join a specific room.
     *
     * @param roomId The identifier of the room to join.
     */
    public void sendJoin(String roomId) {
        send(new JoinMessage(userId, roomId, "Java Client"));

        LOG.info("Sent join message for room: {}", roomId);
    }

    /**
     * Sends a message of a peer to the browser. Called on the peer's event thread, in order.
     *
     * @param message The session description or candidate to send.
     */
    @Override
    public void send(SignalMessage message) {
        switch (message.type()) {
            case OFFER, ANSWER -> {
                String type = message.type() == SignalMessage.Type.OFFER ? "offer" : "answer";

                send(new SessionDescriptionMessage(type, userId, remotePeerId, message.description().sdp));
                LOG.info("Sent {} to remote peer", type);
            }
            case CANDIDATE -> {
                RTCIceCandidate candidate = message.candidate();

                send(new IceCandidateMessage(userId, remotePeerId, candidate.sdp, candidate.sdpMid,
                        candidate.sdpMLineIndex));
            }
            default -> {
                // The browser client expects no end of candidates.
            }
        }
    }

    /**
     * Called when a peer is created with this signaling. The browser's messages go to
     * the peer created last, which replaces the one before.
     *
     * @param inbound Takes the messages the browser sends.
     */
    @Override
    public void attach(Consumer<SignalMessage> inbound) {
        this.inbound = inbound;
    }

    /**
     * Sets the callback for join messages.
     *
     * @param callback The function to be called when a join message is received.
     */
    public void setOnJoinReceived(Consumer<JoinMessage> callback) {
        this.onJoinReceived = callback;
    }

    /**
     * Sets the callback for leave messages.
     *
     * @param callback The function to be called when a leave message is received.
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
     * Sets the remote peer ID that this client is communicating with.
     *
     * @param remotePeerId The remote peer ID to set.
     */
    public void setRemotePeerId(String remotePeerId) {
        this.remotePeerId = remotePeerId;
    }

    /**
     * @return The user ID of this client.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * @return The ID of the remote peer this client communicates with.
     */
    public String getRemotePeerId() {
        return remotePeerId;
    }

    /**
     * @return true if connected to the signaling server.
     */
    public boolean isConnected() {
        return signaling != null && signaling.isConnected();
    }

    /**
     * Sets up the listeners of the WebSocket client.
     */
    private void setupSignaling() {
        signaling.addMessageListener(message -> {
            try {
                String type = jsonMapper.readTree(message).path("type").asText();

                switch (MessageType.fromString(type)) {
                    case OFFER -> receiveDescription(RTCSdpType.OFFER, message);
                    case ANSWER -> receiveDescription(RTCSdpType.ANSWER, message);
                    case ICE_CANDIDATE -> receiveCandidate(message);
                    case JOIN -> notify(onJoinReceived, jsonMapper.readValue(message, JoinMessage.class));
                    case LEAVE -> notify(onLeaveReceived, jsonMapper.readValue(message, LeaveMessage.class));
                    case HEARTBEAT_ACK -> LOG.debug("Received: {}", type);
                    default -> LOG.info("Received message with unknown type: {}", type);
                }
            }
            catch (Exception e) {
                LOG.error("Error handling signaling message", e);
            }
        });

        signaling.addCloseListener(() -> LOG.info("Signaling connection closed"));
        signaling.addErrorListener(error -> LOG.error("Signaling error", error));
    }

    private void receiveDescription(RTCSdpType type, String json) throws Exception {
        SessionDescriptionMessage message = jsonMapper.readValue(json, SessionDescriptionMessage.class);

        receive(SignalMessage.of(new RTCSessionDescription(type, message.getSdp())));
    }

    private void receiveCandidate(String json) throws Exception {
        IceCandidateMessage.IceCandidateData data = jsonMapper.readValue(json, IceCandidateMessage.class).getData();

        receive(SignalMessage.of(new RTCIceCandidate(data.getSdpMid(), data.getSdpMLineIndex(), data.getCandidate())));
    }

    private void receive(SignalMessage message) {
        Consumer<SignalMessage> peer = inbound;

        if (peer != null) {
            peer.accept(message);
        }
        else {
            LOG.warn("Dropped a {} that arrived before there was a peer", message.type());
        }
    }

    private void send(SignalingMessage message) {
        try {
            signaling.sendMessage(jsonMapper.writeValueAsString(message))
                    .exceptionally(error -> {
                        LOG.error("Failed to send a {} message", message.getType(), error);
                        return null;
                    });
        }
        catch (Exception e) {
            LOG.error("Error creating a JSON {} message", message.getType(), e);
        }
    }

    private static <T> void notify(Consumer<T> callback, T message) {
        if (callback != null) {
            callback.accept(message);
        }
    }
}
