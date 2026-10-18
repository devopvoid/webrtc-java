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

package dev.onvoid.webrtc.client;

import dev.onvoid.webrtc.RTCPeerConnectionState;

/**
 * The state of a {@link Peer}.
 */
public enum PeerState {

	/** Created, nothing negotiated yet. */
	NEW,

	/** Negotiating, or ICE and DTLS are establishing the connection. */
	CONNECTING,

	/** Media and data can flow. */
	CONNECTED,

	/** Connectivity was lost. It may come back by itself. */
	DISCONNECTED,

	/** The connection failed. It needs {@link Peer#restartIce()} or a new peer. */
	FAILED,

	/** The peer was closed. This state is final. */
	CLOSED;

	static PeerState of(RTCPeerConnectionState state) {
		switch (state) {
			case NEW:
				return NEW;
			case CONNECTING:
				return CONNECTING;
			case CONNECTED:
				return CONNECTED;
			case DISCONNECTED:
				return DISCONNECTED;
			case FAILED:
				return FAILED;
			default:
				return CLOSED;
		}
	}

}
