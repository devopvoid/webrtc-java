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
 * Represents a session description message used in WebRTC signaling.
 * <p>
 * This class is used for both offer and answer messages, which contain
 * Session Description Protocol (SDP) information.
 * </p>
 *
 * @author Alex Andres
 */
public class SessionDescriptionMessage extends SignalingMessage {

    private SessionDescriptionData data;


    /**
     * Default constructor required for JSON deserialization.
     */
    public SessionDescriptionMessage() {
        super();
    }

    /**
     * Creates a new SessionDescriptionMessage with the specified type, sender, recipient, and SDP.
     *
     * @param type the message type (either "offer" or "answer").
     * @param from the sender ID.
     * @param to   the recipient ID.
     * @param sdp  the SDP string.
     */
    public SessionDescriptionMessage(String type, String from, String to, String sdp) {
        super(type, from, to);

        data = new SessionDescriptionData(sdp);
    }

    /**
     * Gets the session description data.
     *
     * @return the session description data.
     */
    public SessionDescriptionData getData() {
        return data;
    }

    /**
     * Sets the session description data.
     *
     * @param data the session description data.
     */
    public void setData(SessionDescriptionData data) {
        this.data = data;
    }

    /**
     * Sets the SDP string.
     *
     * @param sdp the SDP string.
     */
    public void setSdp(String sdp) {
        if (data == null) {
            data = new SessionDescriptionData();
        }
        data.setSdp(sdp);
    }

    /**
     * Gets the SDP string.
     *
     * @return the SDP string.
     */
    public String getSdp() {
        return data != null ? data.getSdp() : null;
    }



    /**
     * Inner class representing the data payload of a session description message.
     */
    public static class SessionDescriptionData {

        private String sdp;


        /**
         * Default constructor required for JSON deserialization.
         */
        public SessionDescriptionData() {
        }

        /**
         * Creates a new SessionDescriptionData with the specified SDP.
         *
         * @param sdp the SDP string.
         */
        public SessionDescriptionData(String sdp) {
            this.sdp = sdp;
        }

        /**
         * Gets the SDP string.
         *
         * @return the SDP string.
         */
        public String getSdp() {
            return sdp;
        }

        /**
         * Sets the SDP string.
         *
         * @param sdp the SDP string.
         */
        public void setSdp(String sdp) {
            this.sdp = sdp;
        }
    }
}