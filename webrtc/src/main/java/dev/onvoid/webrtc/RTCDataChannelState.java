/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc;

/**
 * Represents the state of the {@link RTCDataChannel}.
 *
 * @author Alex Andres
 */
public enum RTCDataChannelState {

	/**
	 * The user agent is attempting to establish the underlying data transport.
	 * This is the initial state of an RTCDataChannel object.
	 */
	CONNECTING,

	/**
	 * The underlying data transport is established and communication is
	 * possible.
	 */
	OPEN,

	/**
	 * The procedure to close down the underlying data transport has started.
	 */
	CLOSING,

	/**
	 * The underlying data transport has been closed or could not be
	 * established.
	 */
	CLOSED;

}
