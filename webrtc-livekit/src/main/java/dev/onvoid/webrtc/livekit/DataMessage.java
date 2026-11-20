/*
 * Copyright 2026 Alex Andres
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

package dev.onvoid.webrtc.livekit;

import java.util.Optional;

/**
 * Data another participant published to the room.
 */
public final class DataMessage {

	private final String senderIdentity;

	private final String topic;

	private final byte[] payload;

	private final boolean reliable;


	DataMessage(String senderIdentity, String topic, byte[] payload, boolean reliable) {
		this.senderIdentity = senderIdentity;
		this.topic = topic;
		this.payload = payload;
		this.reliable = reliable;
	}

	/**
	 * @return the identity of the sender, empty for data the server sent.
	 */
	public String senderIdentity() {
		return senderIdentity;
	}

	/**
	 * @return the topic the sender gave, if any.
	 */
	public Optional<String> topic() {
		return Optional.ofNullable(topic);
	}

	/**
	 * @return a copy of the payload.
	 */
	public byte[] payload() {
		return payload.clone();
	}

	/**
	 * @return true if it came over the reliable channel, false if over the
	 * lossy one.
	 */
	public boolean isReliable() {
		return reliable;
	}

	@Override
	public String toString() {
		return "DataMessage[from " + senderIdentity + ", topic " + topic + ", " + payload.length + " bytes]";
	}

}
