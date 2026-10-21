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

import dev.onvoid.webrtc.RTCDataChannelState;

/**
 * The state of a {@link DataChannel}.
 */
public enum ChannelState {

	/** Not open yet. Messages sent now are held until it opens. */
	CONNECTING,

	/** Messages can be sent and received. */
	OPEN,

	/** Closing. */
	CLOSING,

	/** Closed. This state is final. */
	CLOSED;

	static ChannelState of(RTCDataChannelState state) {
		switch (state) {
			case CONNECTING:
				return CONNECTING;
			case OPEN:
				return OPEN;
			case CLOSING:
				return CLOSING;
			default:
				return CLOSED;
		}
	}

}
