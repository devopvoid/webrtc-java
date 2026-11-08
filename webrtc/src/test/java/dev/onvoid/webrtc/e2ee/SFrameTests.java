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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Checks the SFrame operations against the test vectors of RFC 9605,
 * Appendix C.
 */
class SFrameTests {

	@Test
	void headersMatchVectors() throws IOException {
		List<Map<String, String>> vectors = vectors("header");

		assertEquals(289, vectors.size());

		for (Map<String, String> vector : vectors) {
			long kid = Long.parseUnsignedLong(vector.get("kid"), 16);
			long ctr = Long.parseUnsignedLong(vector.get("ctr"), 16);
			byte[] expected = hex(vector.get("header"));

			byte[] header = new byte[SFrame.MAX_HEADER_LENGTH];
			int length = SFrame.writeHeader(kid, ctr, header, 0);

			assertArrayEquals(expected, Arrays.copyOf(header, length), vector.toString());
			assertEquals(length, SFrame.headerLength(kid, ctr));

			long[] parsed = new long[2];
			assertEquals(expected.length, SFrame.parseHeader(expected, 0, expected.length, parsed));
			assertEquals(kid, parsed[0]);
			assertEquals(ctr, parsed[1]);
		}
	}

	@Test
	void nonMinimalHeadersRejected() {
		long[] parsed = new long[2];

		// A KID of 5 in an extended field, and a CTR of 0x0001 in two bytes.
		assertEquals(-1, SFrame.parseHeader(new byte[] { (byte) 0x80, 0x05 }, 0, 2, parsed));
		assertEquals(-1, SFrame.parseHeader(new byte[] { 0x09, 0x00, 0x01 }, 0, 3, parsed));
		// Cut short.
		assertEquals(-1, SFrame.parseHeader(new byte[] { 0x09, 0x01 }, 0, 2, parsed));
		assertEquals(-1, SFrame.parseHeader(new byte[0], 0, 0, parsed));
	}

	@Test
	void aeadMatchesVectors() throws IOException {
		List<Map<String, String>> vectors = vectors("aead");

		assertEquals(3, vectors.size());

		for (Map<String, String> vector : vectors) {
			CipherSuite suite = CipherSuite.of(Integer.parseInt(vector.get("cipher_suite"), 16));
			byte[] nonce = hex(vector.get("nonce"));
			byte[] aad = hex(vector.get("aad"));
			byte[] pt = hex(vector.get("pt"));
			byte[] ct = hex(vector.get("ct"));

			SFrame sframe = SFrame.of(suite, 0, hex(vector.get("key")), nonce);

			byte[] sealed = new byte[pt.length + suite.getTagLength()];
			int length = sframe.seal(nonce, aad, 0, aad.length, new byte[0], 0, 0, pt, 0, pt.length, sealed, 0);

			assertEquals(ct.length, length);
			assertArrayEquals(ct, sealed, suite.name());

			byte[] opened = new byte[ct.length];
			length = sframe.open(nonce, aad, 0, aad.length, new byte[0], 0, 0, ct, 0, ct.length, opened, 0);

			assertArrayEquals(pt, Arrays.copyOf(opened, length), suite.name());
		}
	}

	@Test
	void sframeMatchesVectors() throws IOException {
		List<Map<String, String>> vectors = vectors("sframe");

		assertEquals(CipherSuite.values().length, vectors.size());

		for (Map<String, String> vector : vectors) {
			CipherSuite suite = CipherSuite.of(Integer.parseInt(vector.get("cipher_suite"), 16));
			long kid = Long.parseUnsignedLong(vector.get("kid"), 16);
			long ctr = Long.parseUnsignedLong(vector.get("ctr"), 16);
			byte[] baseKey = hex(vector.get("base_key"));
			byte[] metadata = hex(vector.get("metadata"));
			byte[] pt = hex(vector.get("pt"));
			byte[] ct = hex(vector.get("ct"));

			assertArrayEquals(hex(vector.get("sframe_key_label")), SFrame.keyLabel(kid, suite));
			assertArrayEquals(hex(vector.get("sframe_salt_label")), SFrame.saltLabel(kid, suite));
			assertArrayEquals(hex(vector.get("sframe_secret")), SFrame.hkdfExtract(suite, baseKey));

			SFrame sframe = SFrame.derive(suite, kid, baseKey);

			assertArrayEquals(hex(vector.get("nonce")), sframe.nonce(ctr), suite.name());

			byte[] out = new byte[SFrame.MAX_HEADER_LENGTH + pt.length + suite.getTagLength()];
			int length = sframe.encrypt(ctr, metadata, 0, metadata.length, pt, 0, pt.length, out, 0);

			assertArrayEquals(ct, Arrays.copyOf(out, length), suite.name());

			long[] header = new long[2];
			int headerLength = SFrame.parseHeader(ct, 0, ct.length, header);

			assertEquals(kid, header[0]);
			assertEquals(ctr, header[1]);

			byte[] opened = new byte[ct.length];
			length = sframe.decrypt(ctr, ct, 0, headerLength, metadata, 0, metadata.length,
					ct, headerLength, ct.length - headerLength, opened, 0);

			assertArrayEquals(pt, Arrays.copyOf(opened, length), suite.name());
		}
	}

	@Test
	void tamperingFailsAuthentication() throws IOException {
		for (Map<String, String> vector : vectors("sframe")) {
			CipherSuite suite = CipherSuite.of(Integer.parseInt(vector.get("cipher_suite"), 16));
			long kid = Long.parseUnsignedLong(vector.get("kid"), 16);
			long ctr = Long.parseUnsignedLong(vector.get("ctr"), 16);
			byte[] metadata = hex(vector.get("metadata"));
			byte[] ct = hex(vector.get("ct"));

			SFrame sframe = SFrame.derive(suite, kid, hex(vector.get("base_key")));
			int headerLength = SFrame.headerLength(kid, ctr);
			byte[] out = new byte[ct.length];

			for (int i = 0; i < ct.length; i++) {
				byte[] tampered = ct.clone();
				tampered[i] ^= 0x01;

				int length = sframe.decrypt(ctr, tampered, 0, headerLength, metadata, 0, metadata.length,
						tampered, headerLength, tampered.length - headerLength, out, 0);

				assertEquals(-1, length, suite.name() + " accepted a change of byte " + i);
			}

			byte[] otherMetadata = metadata.clone();
			otherMetadata[0] ^= 0x01;

			assertEquals(-1, sframe.decrypt(ctr, ct, 0, headerLength, otherMetadata, 0, otherMetadata.length,
					ct, headerLength, ct.length - headerLength, out, 0));

			// A shorter ciphertext than a tag never authenticates.
			assertFalse(sframe.decrypt(ctr, ct, 0, headerLength, metadata, 0, metadata.length,
					ct, headerLength, suite.getTagLength() - 1, out, 0) >= 0);
		}
	}

	static List<Map<String, String>> vectors(String section) throws IOException {
		List<Map<String, String>> vectors = new ArrayList<>();

		try (InputStream stream = SFrameTests.class.getResourceAsStream("rfc9605-test-vectors.txt");
			 BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.US_ASCII))) {
			String line;

			while ((line = reader.readLine()) != null) {
				if (line.startsWith("#") || line.isBlank()) {
					continue;
				}

				Map<String, String> vector = new HashMap<>();

				for (String field : line.trim().split(" ")) {
					int equals = field.indexOf('=');
					vector.put(field.substring(0, equals), field.substring(equals + 1));
				}

				if (section.equals(vector.get("section"))) {
					vectors.add(vector);
				}
			}
		}

		return vectors;
	}

	static byte[] hex(String hex) {
		byte[] bytes = new byte[hex.length() / 2];

		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
		}

		return bytes;
	}

}
