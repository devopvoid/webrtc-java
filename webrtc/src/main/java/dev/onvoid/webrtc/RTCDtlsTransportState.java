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
 * Datagram Transport Layer Security (DTLS) transport states.
 *
 * @author Alex Andres
 */
public enum RTCDtlsTransportState {

	/**
	 * DTLS has not started negotiating yet.
	 */
	NEW,

	/**
	 * DTLS is in the process of negotiating a secure connection and verifying
	 * the remote fingerprint.
	 */
	CONNECTING,

	/**
	 * DTLS has completed negotiation of a secure connection and verified the
	 * remote fingerprint.
	 */
	CONNECTED,

	/**
	 * The transport has been closed intentionally.
	 */
	CLOSED,

	/**
	 * The transport has failed as the result of an error (such as receipt of an
	 * error alert or failure to validate the remote fingerprint).
	 */
	FAILED;

}
