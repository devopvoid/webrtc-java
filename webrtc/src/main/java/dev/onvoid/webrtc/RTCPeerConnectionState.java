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
 * Indicates the current state of the {@link RTCPeerConnection}.
 *
 * @author Alex Andres
 */
public enum RTCPeerConnectionState {

	/**
	 * None of the previous states apply and all {@link RTCIceTransport}s and
	 * {@link RTCDtlsTransport}s are in the "NEW" or "CLOSED" state, or there
	 * are no transports.
	 */
	NEW,

	/**
	 * None of the previous states apply and all {@link RTCIceTransport}s or
	 * {@link RTCDtlsTransport}s are in the "NEW", "CONNECTING" or "CHECKING"
	 * state.
	 */
	CONNECTING,

	/**
	 * None of the previous states apply and all {@link RTCIceTransport}s and
	 * {@link RTCDtlsTransport}s are in the "CONNECTED", "COMPLETED" or "CLOSED"
	 * state.
	 */
	CONNECTED,

	/**
	 * None of the previous states apply and any {@link RTCIceTransport}s or
	 * {@link RTCDtlsTransport}s are in the "DISCONNECTED" state.
	 */
	DISCONNECTED,

	/**
	 * The previous state doesn't apply and any {@link RTCIceTransport}s or
	 * {@link RTCDtlsTransport}s are in the "FAILED" state.
	 */
	FAILED,

	/**
	 * The RTCPeerConnection is closed.
	 */
	CLOSED;

}
