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

package dev.onvoid.webrtc;

/**
 * The state of an {@link RTCSctpTransport}.
 *
 * @author Alex Andres
 */
public enum RTCSctpTransportState {

	/**
	 * The transport has not started to set up its association yet. Not a
	 * state of the WebRTC specification, which starts with {@link
	 * #CONNECTING}.
	 */
	NEW,

	/**
	 * The transport is setting up its association.
	 */
	CONNECTING,

	/**
	 * The association is set up, and data channels can open.
	 */
	CONNECTED,

	/**
	 * The association was closed, by either peer or because the DTLS
	 * transport closed.
	 */
	CLOSED

}
