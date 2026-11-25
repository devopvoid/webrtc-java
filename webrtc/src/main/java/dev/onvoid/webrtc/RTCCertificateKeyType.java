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
 * The kind of key a certificate made by {@link
 * RTCCertificatePEM#generate(RTCCertificateKeyType)} has.
 *
 * @author Alex Andres
 */
public enum RTCCertificateKeyType {

	/**
	 * An ECDSA key on the NIST P-256 curve. It is what WebRTC uses by default,
	 * and is made in a fraction of the time an RSA key takes.
	 */
	ECDSA,

	/**
	 * An RSA key of 2048 bits, for peers that do not accept ECDSA.
	 */
	RSA

}
