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

package dev.onvoid.webrtc.examples.web.connection;

import dev.onvoid.webrtc.examples.web.model.IceCandidateMessage;
import dev.onvoid.webrtc.examples.web.model.SessionDescriptionMessage;

/**
 * This interface defines methods for processing signaling messages
 * directly related to WebRTC peer connections.
 *
 * @author Alex Andres
 */
public interface PeerConnectionSignalingHandler {

    /**
     * Handles an incoming offer from a remote peer.
     *
     * @param message The session description message containing the offer.
     */
    void handleOffer(SessionDescriptionMessage message);

    /**
     * Handles an incoming answer from a remote peer.
     *
     * @param message The session description message containing the answer.
     */
    void handleAnswer(SessionDescriptionMessage message);

    /**
     * Handles an incoming ICE candidate from a remote peer.
     *
     * @param message The message containing the ICE candidate.
     */
    void handleCandidate(IceCandidateMessage message);

}