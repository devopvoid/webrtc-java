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

package dev.onvoid.webrtc.examples.web.model;

/**
 * Represents the types of messages exchanged during WebRTC signaling.
 * Each message type corresponds to a specific action or state in the WebRTC connection process.
 *
 * @author Alex Andres
 */
public enum MessageType {

    /** WebRTC offer message containing session description. */
    OFFER("offer"),

    /** WebRTC answer message containing session description. */
    ANSWER("answer"),

    /** ICE candidate information for establishing peer connections. */
    ICE_CANDIDATE("ice-candidate"),

    /** Message indicating a peer joining the signaling session. */
    JOIN("join"),

    /** Message indicating a peer leaving the signaling session. */
    LEAVE("leave"),

    /** Heartbeat message to maintain connection. */
    HEARTBEAT("heartbeat"),

    /** Acknowledgment response to a heartbeat message. */
    HEARTBEAT_ACK("heartbeat-ack"),

    /** Fallback type for unrecognized messages. */
    UNKNOWN("");

    /** The string representation of the message type. */
    private final String value;


    /**
     * Constructs a message type with the specified string value.
     *
     * @param value The string representation of this message type
     */
    MessageType(String value) {
        this.value = value;
    }

    /**
     * Returns the string representation of this message type.
     *
     * @return The string value of this message type
     */
    public String getValue() {
        return value;
    }

    /**
     * Finds and returns the message type enum constant that matches the given string.
     *
     * @param text The string to convert to a message type
     * @return The matching message type, or UNKNOWN if no match is found
     */
    public static MessageType fromString(String text) {
        for (MessageType type : MessageType.values()) {
            if (type.value.equals(text)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
