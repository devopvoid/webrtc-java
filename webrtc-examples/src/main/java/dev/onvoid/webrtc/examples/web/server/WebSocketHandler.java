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

package dev.onvoid.webrtc.examples.web.server;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.onvoid.webrtc.examples.web.model.LeaveMessage;
import dev.onvoid.webrtc.examples.web.model.MessageType;
import dev.onvoid.webrtc.examples.web.model.SignalingMessage;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles WebSocket connections and messages for the WebRTC signaling server.
 * <p>
 * This class manages all active WebSocket sessions, processes incoming messages,
 * and provides methods for broadcasting messages to connected clients.
 * </p>
 *
 * @author Alex Andres
 */
@WebSocket
public class WebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketHandler.class);

    /** Thread-safe set to store all active sessions. */
    private static final Set<Session> sessions = new CopyOnWriteArraySet<>();

    /** Thread-safe map to store session to user-id mappings. */
    private static final Map<Session, String> sessionToUserId = new ConcurrentHashMap<>();

    /** JSON serializer/deserializer for processing WebSocket messages. */
    private final ObjectMapper jsonMapper = new ObjectMapper();


    @OnWebSocketOpen
    public void onOpen(Session session) {
        sessions.add(session);

        LOG.info("New connection: {} (Total connections: {})", session.getRemoteSocketAddress(), sessions.size());
    }

    @OnWebSocketMessage
    public void onMessage(Session session, String message) {
        LOG.info("Message from {}: {}", session.getRemoteSocketAddress(), message);

        try {
            JsonNode messageNode = jsonMapper.readTree(message);
            String type = messageNode.path("type").asText();

            // Check if this is a heartbeat message.
            if (MessageType.HEARTBEAT.getValue().equals(type)) {
                // Send heartbeat acknowledgment.
                SignalingMessage heartbeatAck = new SignalingMessage("heartbeat-ack");
                String heartbeatAckJson = jsonMapper.writeValueAsString(heartbeatAck);

                sendMessage(session, heartbeatAckJson);
                return;
            }
            
            // Check if this is a join message and extract the user-id.
            if (MessageType.JOIN.getValue().equals(type)) {
                String userId = messageNode.path("from").asText();
                
                if (userId != null && !userId.isEmpty()) {
                    // Store the user-id for this session.
                    sessionToUserId.put(session, userId);

                    LOG.info("Registered user-id '{}' for session {}", userId, session.getRemoteSocketAddress());
                }
            }
        }
        catch (JsonProcessingException e) {
            LOG.error("Error parsing message type", e);
        }

        // Broadcast the message to all other connected peers (excluding sender).
        broadcastToOthers(session, message);
    }

    @OnWebSocketClose
    public void onClose(Session session, int statusCode, String reason) {
        sessions.remove(session);

        LOG.info("Connection closed: {} ({}) - Status: {}", session.getRemoteSocketAddress(), reason, statusCode);
        LOG.info("Remaining connections: {}", sessions.size());

        // Notify other clients about disconnection.
        try {
            // Get the user-id for this session or use the socket address as a fallback.
            String userId = sessionToUserId.getOrDefault(session, session.getRemoteSocketAddress().toString());

            // Create a leave message.
            LeaveMessage leaveMessage = new LeaveMessage();
            leaveMessage.setFrom(userId);

            String leaveMessageJson = jsonMapper.writeValueAsString(leaveMessage);
            broadcastToAll(leaveMessageJson);

            LOG.info("Sent participant left message for user-id: {}", userId);

            // Clean up the session-to-userId mapping.
            sessionToUserId.remove(session);
        }
        catch (JsonProcessingException e) {
            LOG.error("Error creating participant left message", e);
        }
    }

    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        LOG.error("WebSocket error for {}", session.getRemoteSocketAddress(), error);
    }

    /**
     * Broadcasts a message to all connected peers except the sender.
     *
     * @param sender  the session of the sender.
     * @param message the message to broadcast.
     */
    private void broadcastToOthers(Session sender, String message) {
        sessions.stream()
                .filter(Session::isOpen)
                .filter(session -> !session.equals(sender))
                .forEach(session -> sendMessage(session, message));
    }

    /**
     * Broadcasts a message to all connected peers.
     *
     * @param message the message to broadcast.
     */
    private void broadcastToAll(String message) {
        sessions.stream()
                .filter(Session::isOpen)
                .forEach(session -> sendMessage(session, message));
    }

    private void sendMessage(Session session, String message) {
        session.sendText(message, Callback.from(() -> {}, throwable -> {
            LOG.error("Error sending message to session: {}", throwable.getMessage());
            // Remove broken sessions.
            sessions.remove(session);
        }));
    }
}
