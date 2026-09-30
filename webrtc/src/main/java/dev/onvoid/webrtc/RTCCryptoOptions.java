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
 * The ciphers a peer connection offers to protect its media with, as {@link
 * RTCConfiguration#cryptoOptions} takes them. A new instance holds WebRTC's
 * defaults.
 *
 * @author Alex Andres
 */
public class RTCCryptoOptions {

	/** Whether to offer the AES-GCM SRTP cipher suites. Default true. */
	public boolean enableGcmCryptoSuites = true;

	/**
	 * Whether to prefer the AES-GCM cipher suites over the others offered.
	 * Default false.
	 */
	public boolean preferGcmCryptoSuites;

	/**
	 * Whether to offer AES_CM_128_HMAC_SHA1_32, a legacy cipher suite with a
	 * shorter authentication tag. Default false.
	 */
	public boolean enableAes128Sha1_32CryptoCipher;

	/**
	 * Whether to offer AES_CM_128_HMAC_SHA1_80. Default true.
	 */
	public boolean enableAes128Sha1_80CryptoCipher = true;

	/**
	 * Whether to encrypt the RTP header extensions that can be encrypted (RFC
	 * 6904). Default true.
	 */
	public boolean enableEncryptedRtpHeaderExtensions = true;

	/**
	 * Whether to encrypt the header extensions and CSRCs as a whole (cryptex).
	 * Default {@link RTCCryptexPolicy#DISABLED}.
	 */
	public RTCCryptexPolicy cryptexPolicy = RTCCryptexPolicy.DISABLED;


	@Override
	public String toString() {
		return String.format("%s [enableGcmCryptoSuites=%s, preferGcmCryptoSuites=%s, "
						+ "enableAes128Sha1_32CryptoCipher=%s, enableAes128Sha1_80CryptoCipher=%s, "
						+ "enableEncryptedRtpHeaderExtensions=%s, cryptexPolicy=%s]",
				RTCCryptoOptions.class.getSimpleName(), enableGcmCryptoSuites,
				preferGcmCryptoSuites, enableAes128Sha1_32CryptoCipher,
				enableAes128Sha1_80CryptoCipher, enableEncryptedRtpHeaderExtensions,
				cryptexPolicy);
	}

}
