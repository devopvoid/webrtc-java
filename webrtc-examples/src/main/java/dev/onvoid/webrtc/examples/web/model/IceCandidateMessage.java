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
 * Represents an ICE candidate message used in WebRTC signaling.
 * <p>
 * This class is used to exchange ICE candidates between peers during
 * the connection establishment process.
 * </p>
 *
 * @author Alex Andres
 */
public class IceCandidateMessage extends SignalingMessage {

    private IceCandidateData data;


    /**
     * Default constructor required for JSON deserialization.
     */
    public IceCandidateMessage() {
        super(MessageType.ICE_CANDIDATE.getValue());
    }

    /**
     * Creates a new IceCandidateMessage with the specified sender and recipient.
     *
     * @param from the sender ID.
     * @param to   the recipient ID.
     */
    public IceCandidateMessage(String from, String to) {
        super(MessageType.ICE_CANDIDATE.getValue(), from, to);

        data = new IceCandidateData();
    }

    /**
     * Creates a new IceCandidateMessage with the specified sender, recipient, and candidate information.
     *
     * @param from          the sender ID.
     * @param to            the recipient ID.
     * @param candidate     the candidate string.
     * @param sdpMid        the SDP mid.
     * @param sdpMLineIndex the SDP mline index.
     */
    public IceCandidateMessage(String from, String to, String candidate, String sdpMid, int sdpMLineIndex) {
        super(MessageType.ICE_CANDIDATE.getValue(), from, to);

        data = new IceCandidateData(candidate, sdpMid, sdpMLineIndex);
    }

    /**
     * Gets the ICE candidate data.
     *
     * @return the ICE candidate data.
     */
    public IceCandidateData getData() {
        return data;
    }

    /**
     * Sets the ICE candidate data.
     *
     * @param data the ICE candidate data.
     */
    public void setData(IceCandidateData data) {
        this.data = data;
    }



    /**
     * Inner class representing the data payload of an ICE candidate message.
     */
    public static class IceCandidateData {

        private String candidate;
        private String sdpMid;
        private int sdpMLineIndex;


        /**
         * Default constructor required for JSON deserialization.
         */
        public IceCandidateData() {
        }

        /**
         * Creates a new IceCandidateData with the specified candidate information.
         *
         * @param candidate     the candidate string.
         * @param sdpMid        the SDP mid.
         * @param sdpMLineIndex the SDP mline index.
         */
        public IceCandidateData(String candidate, String sdpMid, int sdpMLineIndex) {
            this.candidate = candidate;
            this.sdpMid = sdpMid;
            this.sdpMLineIndex = sdpMLineIndex;
        }

        /**
         * Gets the candidate string.
         *
         * @return the candidate string.
         */
        public String getCandidate() {
            return candidate;
        }

        /**
         * Sets the candidate string.
         *
         * @param candidate the candidate string.
         */
        public void setCandidate(String candidate) {
            this.candidate = candidate;
        }

        /**
         * Gets the SDP mid.
         *
         * @return the SDP mid.
         */
        public String getSdpMid() {
            return sdpMid;
        }

        /**
         * Sets the SDP mid.
         *
         * @param sdpMid the SDP mid.
         */
        public void setSdpMid(String sdpMid) {
            this.sdpMid = sdpMid;
        }

        /**
         * Gets the SDP mline index.
         *
         * @return the SDP mline index.
         */
        public int getSdpMLineIndex() {
            return sdpMLineIndex;
        }

        /**
         * Sets the SDP mline index.
         *
         * @param sdpMLineIndex the SDP mline index.
         */
        public void setSdpMLineIndex(int sdpMLineIndex) {
            this.sdpMLineIndex = sdpMLineIndex;
        }
    }
}