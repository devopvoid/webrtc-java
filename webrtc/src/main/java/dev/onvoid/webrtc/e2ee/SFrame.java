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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The SFrame operations of RFC 9605: the header encoding (Section 4.3), the
 * key and salt derivation (Section 4.4.2), and the encryption and decryption
 * of one SFrame ciphertext (Sections 4.4.3 to 4.5.1).
 * <p>
 * An instance is the derived key of one KID, and may be used from any
 * thread: the JCA objects it needs are kept per thread.
 */
final class SFrame {

	private static final byte[] KEY_LABEL = "SFrame 1.0 Secret key ".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] SALT_LABEL = "SFrame 1.0 Secret salt ".getBytes(StandardCharsets.US_ASCII);

	/** The longest header: the config byte, an 8-byte KID and an 8-byte CTR. */
	static final int MAX_HEADER_LENGTH = 17;

	private static final ThreadLocal<Cipher> GCM = ThreadLocal.withInitial(() -> cipher("AES/GCM/NoPadding"));

	private static final ThreadLocal<Cipher> CTR = ThreadLocal.withInitial(() -> cipher("AES/CTR/NoPadding"));

	private static final ThreadLocal<Map<String, Mac>> MACS = ThreadLocal.withInitial(HashMap::new);

	private final CipherSuite suite;

	private final long kid;

	/** The AES key: all of sframe_key for GCM, its first Nka bytes for CTR. */
	private final SecretKeySpec aesKey;

	/** The HMAC key of the CTR suites, the rest of sframe_key; null for GCM. */
	private final SecretKeySpec authKey;

	private final byte[] salt;


	private SFrame(CipherSuite suite, long kid, byte[] sframeKey, byte[] salt) {
		this.suite = suite;
		this.kid = kid;
		this.salt = salt;

		if (suite.isGcm()) {
			this.aesKey = new SecretKeySpec(sframeKey, "AES");
			this.authKey = null;
		}
		else {
			int split = suite.aesKeyLength();

			this.aesKey = new SecretKeySpec(sframeKey, 0, split, "AES");
			this.authKey = new SecretKeySpec(sframeKey, split, sframeKey.length - split, suite.hmac());
		}
	}

	/** Wraps an sframe_key and sframe_salt that are already derived. */
	static SFrame of(CipherSuite suite, long kid, byte[] sframeKey, byte[] salt) {
		return new SFrame(suite, kid, sframeKey.clone(), salt.clone());
	}

	/**
	 * Derives the key and salt of a KID from a base key, as
	 * {@code derive_key_salt} does.
	 */
	static SFrame derive(CipherSuite suite, long kid, byte[] baseKey) {
		byte[] secret = hkdfExtract(suite, baseKey);

		try {
			byte[] key = hkdfExpand(suite, secret, label(KEY_LABEL, kid, suite), suite.keyLength());
			byte[] salt = hkdfExpand(suite, secret, label(SALT_LABEL, kid, suite), CipherSuite.NONCE_LENGTH);

			return new SFrame(suite, kid, key, salt);
		}
		finally {
			Arrays.fill(secret, (byte) 0);
		}
	}

	long kid() {
		return kid;
	}

	/**
	 * Encrypts a plaintext with the given counter: writes the SFrame header
	 * at {@code out[outOffset]} followed by the ciphertext and its tag. The
	 * metadata is authenticated with the header but not written.
	 *
	 * @return The number of bytes written.
	 */
	int encrypt(long ctr, byte[] metadata, int metadataOffset, int metadataLength,
			byte[] plaintext, int plaintextOffset, int plaintextLength, byte[] out, int outOffset) {
		int headerLength = writeHeader(kid, ctr, out, outOffset);

		return headerLength + seal(nonce(ctr), out, outOffset, headerLength, metadata, metadataOffset,
				metadataLength, plaintext, plaintextOffset, plaintextLength, out, outOffset + headerLength);
	}

	/**
	 * AEAD.Encrypt with the AAD in two parts, the header and the metadata.
	 *
	 * @return The size of the ciphertext with its tag.
	 */
	int seal(byte[] nonce, byte[] header, int headerOffset, int headerLength,
			byte[] metadata, int metadataOffset, int metadataLength,
			byte[] plaintext, int plaintextOffset, int plaintextLength, byte[] out, int outOffset) {
		try {
			if (suite.isGcm()) {
				Cipher cipher = GCM.get();
				cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(suite.getTagLength() * 8, nonce));
				cipher.updateAAD(header, headerOffset, headerLength);
				cipher.updateAAD(metadata, metadataOffset, metadataLength);

				return cipher.doFinal(plaintext, plaintextOffset, plaintextLength, out, outOffset);
			}

			Cipher cipher = CTR.get();
			cipher.init(Cipher.ENCRYPT_MODE, aesKey, new IvParameterSpec(counterBlock(nonce)));
			int ctLength = cipher.doFinal(plaintext, plaintextOffset, plaintextLength, out, outOffset);

			byte[] tag = tag(nonce, header, headerOffset, headerLength, metadata, metadataOffset, metadataLength,
					out, outOffset, ctLength);
			System.arraycopy(tag, 0, out, outOffset + ctLength, suite.getTagLength());

			return ctLength + suite.getTagLength();
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Encrypting a frame failed", e);
		}
	}

	/**
	 * Decrypts the ciphertext and tag that follow a parsed header, writing
	 * the plaintext to {@code out[outOffset]}.
	 *
	 * @return The size of the plaintext, or -1 if the frame did not
	 * authenticate.
	 */
	int decrypt(long ctr, byte[] header, int headerOffset, int headerLength,
			byte[] metadata, int metadataOffset, int metadataLength,
			byte[] ciphertext, int ciphertextOffset, int ciphertextLength, byte[] out, int outOffset) {
		return open(nonce(ctr), header, headerOffset, headerLength, metadata, metadataOffset, metadataLength,
				ciphertext, ciphertextOffset, ciphertextLength, out, outOffset);
	}

	/**
	 * AEAD.Decrypt with the AAD in two parts, the header and the metadata.
	 *
	 * @return The size of the plaintext, or -1 if it did not authenticate.
	 */
	int open(byte[] nonce, byte[] header, int headerOffset, int headerLength,
			byte[] metadata, int metadataOffset, int metadataLength,
			byte[] ciphertext, int ciphertextOffset, int ciphertextLength, byte[] out, int outOffset) {
		int tagLength = suite.getTagLength();

		if (ciphertextLength < tagLength) {
			return -1;
		}

		try {
			if (suite.isGcm()) {
				Cipher cipher = GCM.get();
				cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(tagLength * 8, nonce));
				cipher.updateAAD(header, headerOffset, headerLength);
				cipher.updateAAD(metadata, metadataOffset, metadataLength);

				return cipher.doFinal(ciphertext, ciphertextOffset, ciphertextLength, out, outOffset);
			}

			int innerLength = ciphertextLength - tagLength;
			byte[] expected = tag(nonce, header, headerOffset, headerLength, metadata, metadataOffset,
					metadataLength, ciphertext, ciphertextOffset, innerLength);

			byte[] actual = Arrays.copyOfRange(ciphertext, ciphertextOffset + innerLength,
					ciphertextOffset + ciphertextLength);

			if (!MessageDigest.isEqual(actual, Arrays.copyOf(expected, tagLength))) {
				return -1;
			}

			Cipher cipher = CTR.get();
			cipher.init(Cipher.DECRYPT_MODE, aesKey, new IvParameterSpec(counterBlock(nonce)));

			return cipher.doFinal(ciphertext, ciphertextOffset, innerLength, out, outOffset);
		}
		catch (AEADBadTagException e) {
			return -1;
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException("Decrypting a frame failed", e);
		}
	}

	/**
	 * The nonce of a counter: the salt XORed with the counter as a big-endian
	 * integer of the nonce's size.
	 */
	byte[] nonce(long ctr) {
		byte[] nonce = salt.clone();

		for (int i = 0; i < Long.BYTES; i++) {
			nonce[nonce.length - 1 - i] ^= (byte) (ctr >>> (8 * i));
		}

		return nonce;
	}

	/**
	 * Returns the size of the header for a KID and CTR. Both are unsigned
	 * 64-bit values.
	 */
	static int headerLength(long kid, long ctr) {
		return 1 + extendedLength(kid) + extendedLength(ctr);
	}

	/**
	 * Writes the header for a KID and CTR, as {@code encode_sframe_header}
	 * does.
	 *
	 * @return The number of bytes written.
	 */
	static int writeHeader(long kid, long ctr, byte[] out, int offset) {
		int kidLength = extendedLength(kid);
		int ctrLength = extendedLength(ctr);

		int config = 0;
		config |= kidLength == 0 ? (int) kid << 4 : 0x80 | (kidLength - 1) << 4;
		config |= ctrLength == 0 ? (int) ctr : 0x08 | (ctrLength - 1);

		out[offset] = (byte) config;

		writeBigEndian(kid, kidLength, out, offset + 1);
		writeBigEndian(ctr, ctrLength, out, offset + 1 + kidLength);

		return 1 + kidLength + ctrLength;
	}

	/**
	 * Parses a header. Only the minimal encoding RFC 9605 requires is
	 * accepted.
	 *
	 * @param values Receives the KID at index 0 and the CTR at index 1.
	 *
	 * @return The size of the header, or -1 if the bytes are no valid header.
	 */
	static int parseHeader(byte[] in, int offset, int length, long[] values) {
		if (length < 1) {
			return -1;
		}

		int config = in[offset] & 0xFF;
		int position = 1;

		for (int field = 0; field < 2; field++) {
			int bits = field == 0 ? config >>> 4 : config;
			boolean extended = (bits & 0x08) != 0;
			int value = bits & 0x07;

			if (!extended) {
				values[field] = value;
				continue;
			}

			int size = value + 1;

			if (length - position < size || in[offset + position] == 0) {
				// Too short, or not the shortest encoding.
				return -1;
			}

			long decoded = 0;

			for (int i = 0; i < size; i++) {
				decoded = decoded << 8 | (in[offset + position + i] & 0xFF);
			}

			if (Long.compareUnsigned(decoded, 8) < 0) {
				return -1;
			}

			values[field] = decoded;
			position += size;
		}

		return position;
	}

	private byte[] tag(byte[] nonce, byte[] header, int headerOffset, int headerLength,
			byte[] metadata, int metadataOffset, int metadataLength,
			byte[] ct, int ctOffset, int ctLength) throws GeneralSecurityException {
		Mac mac = mac(suite.hmac());
		mac.init(authKey);

		byte[] lengths = new byte[24];
		writeBigEndian(headerLength + metadataLength, 8, lengths, 0);
		writeBigEndian(ctLength, 8, lengths, 8);
		writeBigEndian(suite.getTagLength(), 8, lengths, 16);

		mac.update(lengths);
		mac.update(nonce);
		mac.update(header, headerOffset, headerLength);
		mac.update(metadata, metadataOffset, metadataLength);
		mac.update(ct, ctOffset, ctLength);

		return mac.doFinal();
	}

	/** The initial counter block of AES-CTR: the nonce and four zero bytes. */
	private static byte[] counterBlock(byte[] nonce) {
		return Arrays.copyOf(nonce, 16);
	}

	static byte[] hkdfExtract(CipherSuite suite, byte[] ikm) {
		try {
			Mac mac = mac(suite.hmac());
			// An empty salt is a string of zeros as long as the hash
			// (RFC 5869, Section 2.2), which HMAC pads alike.
			mac.init(new SecretKeySpec(new byte[suite.getHashLength()], suite.hmac()));

			return mac.doFinal(ikm);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	static byte[] hkdfExpand(CipherSuite suite, byte[] prk, byte[] info, int length) {
		try {
			Mac mac = mac(suite.hmac());
			mac.init(new SecretKeySpec(prk, suite.hmac()));

			byte[] okm = new byte[length];
			byte[] block = new byte[0];
			int written = 0;

			for (int i = 1; written < length; i++) {
				mac.update(block);
				mac.update(info);
				mac.update((byte) i);
				block = mac.doFinal();

				int n = Math.min(block.length, length - written);
				System.arraycopy(block, 0, okm, written, n);
				written += n;
			}

			return okm;
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	/** A label followed by the KID as 8 bytes and the suite as 2 bytes. */
	static byte[] label(byte[] prefix, long kid, CipherSuite suite) {
		byte[] label = Arrays.copyOf(prefix, prefix.length + 10);

		writeBigEndian(kid, 8, label, prefix.length);
		writeBigEndian(suite.getId(), 2, label, prefix.length + 8);

		return label;
	}

	static byte[] keyLabel(long kid, CipherSuite suite) {
		return label(KEY_LABEL, kid, suite);
	}

	static byte[] saltLabel(long kid, CipherSuite suite) {
		return label(SALT_LABEL, kid, suite);
	}

	/** The number of bytes after the config byte for a value: 0 if below 8. */
	private static int extendedLength(long value) {
		if (Long.compareUnsigned(value, 8) < 0) {
			return 0;
		}

		return (Long.SIZE - Long.numberOfLeadingZeros(value) + 7) / 8;
	}

	private static void writeBigEndian(long value, int length, byte[] out, int offset) {
		for (int i = 0; i < length; i++) {
			out[offset + i] = (byte) (value >>> (8 * (length - 1 - i)));
		}
	}

	private static Mac mac(String algorithm) {
		return MACS.get().computeIfAbsent(algorithm, name -> {
			try {
				return Mac.getInstance(name);
			}
			catch (GeneralSecurityException e) {
				throw new IllegalStateException(name + " is not available", e);
			}
		});
	}

	private static Cipher cipher(String transformation) {
		try {
			return Cipher.getInstance(transformation);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(transformation + " is not available", e);
		}
	}

}
