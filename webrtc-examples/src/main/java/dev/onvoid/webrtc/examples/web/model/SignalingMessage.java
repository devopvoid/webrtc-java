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
 * Base class for all signaling messages.
 * <p>
 * This class represents the common structure of all messages exchanged
 * during WebRTC signaling. Specific message types extend this class
 * and add their own data payload.
 * </p>
 *
 * @author Alex Andres
 */
public class SignalingMessage {

    private String type;
    private String from;
    private String to;


    /**
     * Default constructor required for JSON deserialization.
     */
    public SignalingMessage() {
    }

    /**
     * Creates a new SignalingMessage with the specified type.
     *
     * @param type the message type.
     */
    public SignalingMessage(String type) {
        this.type = type;
    }

    /**
     * Creates a new SignalingMessage with the specified type, sender, and recipient.
     *
     * @param type the message type.
     * @param from the sender ID.
     * @param to   the recipient ID.
     */
    public SignalingMessage(String type, String from, String to) {
        this.type = type;
        this.from = from;
        this.to = to;
    }

    /**
     * Gets the message type.
     *
     * @return the message type.
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the message type.
     *
     * @param type the message type.
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * Gets the sender ID.
     *
     * @return the sender ID.
     */
    public String getFrom() {
        return from;
    }

    /**
     * Sets the sender ID.
     *
     * @param from the sender ID.
     */
    public void setFrom(String from) {
        this.from = from;
    }

    /**
     * Gets the recipient ID.
     *
     * @return the recipient ID.
     */
    public String getTo() {
        return to;
    }

    /**
     * Sets the recipient ID.
     *
     * @param to the recipient ID.
     */
    public void setTo(String to) {
        this.to = to;
    }
}