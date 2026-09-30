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
 * Whether SRTP encrypts the RTP header extensions and CSRCs as a whole
 * (cryptex, RFC 9335), as {@link RTCCryptoOptions#cryptexPolicy} takes it.
 *
 * @author Alex Andres
 */
public enum RTCCryptexPolicy {

	/**
	 * Do not use cryptex.
	 */
	DISABLED,

	/**
	 * Use cryptex if the peer supports it.
	 */
	NEGOTIATE,

	/**
	 * Use cryptex, and fail with a peer that does not support it.
	 */
	REQUIRE

}
