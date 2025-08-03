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
 * Represents a message indicating that a user is leaving a room.
 * This class is used when a peer leaves a room or session.
 *
 * @author Alex Andres
 */
public class LeaveMessage extends SignalingMessage {

    /**
     * Constructs a new leave message with default values.
     */
    public LeaveMessage() {
        super(MessageType.LEAVE.getValue());
    }

}
