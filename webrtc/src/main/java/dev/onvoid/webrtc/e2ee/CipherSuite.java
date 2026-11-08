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

package dev.onvoid.webrtc.e2ee;

/**
 * The SFrame cipher suites of RFC 9605, Section 4.5. Every participant of a
 * call has to use the same one, since nothing in a frame says which it is.
 * <p>
 * The GCM suites have full 16-byte tags and are what browsers implement
 * fastest with WebCrypto. The CTR suites trade a shorter tag, down to 4
 * bytes, for a higher risk of forgeries (RFC 9605, Section 7.5); the replay
 * window of the {@link KeyProvider} is one of the mitigations the RFC names.
 *
 * @author Alex Andres
 */
public enum CipherSuite {

	/** AES-128 in counter mode with an 80-bit HMAC-SHA256 tag. */
	AES_128_CTR_HMAC_SHA256_80(0x0001, 16, 48, 10, false, "HmacSHA256", 32),

	/** AES-128 in counter mode with a 64-bit HMAC-SHA256 tag. */
	AES_128_CTR_HMAC_SHA256_64(0x0002, 16, 48, 8, false, "HmacSHA256", 32),

	/** AES-128 in counter mode with a 32-bit HMAC-SHA256 tag. */
	AES_128_CTR_HMAC_SHA256_32(0x0003, 16, 48, 4, false, "HmacSHA256", 32),

	/** AES-128-GCM with a 128-bit tag and HKDF-SHA256, the default. */
	AES_128_GCM_SHA256_128(0x0004, 16, 16, 16, true, "HmacSHA256", 32),

	/** AES-256-GCM with a 128-bit tag and HKDF-SHA512. */
	AES_256_GCM_SHA512_128(0x0005, 32, 32, 16, true, "HmacSHA512", 64);

	/** The size of every nonce, Nn. */
	static final int NONCE_LENGTH = 12;

	private final int id;

	/** The AES key size, Nka for the CTR suites. */
	private final int aesKeyLength;

	/** The size of the AEAD key, Nk. */
	private final int keyLength;

	/** The size of the tag, Nt. */
	private final int tagLength;

	private final boolean gcm;

	private final String hmac;

	/** The size of the hash output, Nh. */
	private final int hashLength;


	CipherSuite(int id, int aesKeyLength, int keyLength, int tagLength, boolean gcm, String hmac,
			int hashLength) {
		this.id = id;
		this.aesKeyLength = aesKeyLength;
		this.keyLength = keyLength;
		this.tagLength = tagLength;
		this.gcm = gcm;
		this.hmac = hmac;
		this.hashLength = hashLength;
	}

	/**
	 * @return The identifier of the suite in the IANA "SFrame Cipher Suites"
	 * registry, e.g. {@code 0x0004}.
	 */
	public int getId() {
		return id;
	}

	/**
	 * @return The size of the authentication tag each frame carries, in
	 * bytes.
	 */
	public int getTagLength() {
		return tagLength;
	}

	/**
	 * @return The size of the keys {@link KeyProvider#generateKey()} makes,
	 * the output size of the suite's hash, in bytes.
	 */
	public int getHashLength() {
		return hashLength;
	}

	/**
	 * Returns the suite with the given registry identifier.
	 *
	 * @param id The identifier, e.g. {@code 0x0004}.
	 *
	 * @return The suite.
	 *
	 * @throws IllegalArgumentException If no suite has this identifier.
	 */
	public static CipherSuite of(int id) {
		for (CipherSuite suite : values()) {
			if (suite.id == id) {
				return suite;
			}
		}

		throw new IllegalArgumentException(String.format("Unknown cipher suite 0x%04x", id));
	}

	int aesKeyLength() {
		return aesKeyLength;
	}

	int keyLength() {
		return keyLength;
	}

	boolean isGcm() {
		return gcm;
	}

	String hmac() {
		return hmac;
	}

}
