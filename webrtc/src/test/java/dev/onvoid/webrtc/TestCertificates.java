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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Creates self-signed certificates for tests, with a fresh key each time, so
 * that no private key has to be kept in the repository.
 */
final class TestCertificates {

	// ecdsa-with-SHA256, 1.2.840.10045.4.3.2
	private static final byte[] ECDSA_WITH_SHA256 = {
			0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02
	};

	// commonName, 2.5.4.3
	private static final byte[] COMMON_NAME = { 0x06, 0x03, 0x55, 0x04, 0x03 };

	private static final int INTEGER = 0x02;
	private static final int BIT_STRING = 0x03;
	private static final int UTF8_STRING = 0x0C;
	private static final int UTC_TIME = 0x17;
	private static final int SEQUENCE = 0x30;
	private static final int SET = 0x31;
	private static final int VERSION = 0xA0;


	private TestCertificates() {
	}

	/**
	 * Creates a self-signed P-256 certificate.
	 *
	 * @param notBefore The start of the validity, as UTCTime, e.g. "260101000000Z".
	 * @param notAfter  The end of the validity, as UTCTime, e.g. "491231235959Z".
	 *
	 * @return The certificate with its private key, both PEM encoded.
	 */
	static RTCCertificatePEM selfSigned(String notBefore, String notAfter)
			throws GeneralSecurityException {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));

		KeyPair keyPair = generator.generateKeyPair();

		byte[] algorithm = der(SEQUENCE, ECDSA_WITH_SHA256);
		byte[] name = der(SEQUENCE, der(SET, der(SEQUENCE, COMMON_NAME,
				der(UTF8_STRING, ascii("webrtc-java-test")))));

		byte[] tbsCertificate = der(SEQUENCE,
				der(VERSION, der(INTEGER, new byte[] { 2 })),
				der(INTEGER, new byte[] { 1 }),
				algorithm,
				name,
				der(SEQUENCE, der(UTC_TIME, ascii(notBefore)), der(UTC_TIME, ascii(notAfter))),
				name,
				keyPair.getPublic().getEncoded());

		Signature signer = Signature.getInstance("SHA256withECDSA");
		signer.initSign(keyPair.getPrivate());
		signer.update(tbsCertificate);

		byte[] certificate = der(SEQUENCE, tbsCertificate, algorithm,
				der(BIT_STRING, new byte[] { 0 }, signer.sign()));

		return new RTCCertificatePEM(
				pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()),
				pem("CERTIFICATE", certificate),
				RTCCertificatePEM.EXPIRES_UNKNOWN);
	}

	private static byte[] der(int tag, byte[]... contents) {
		ByteArrayOutputStream value = new ByteArrayOutputStream();

		for (byte[] content : contents) {
			value.writeBytes(content);
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(tag);

		int length = value.size();

		if (length < 0x80) {
			out.write(length);
		}
		else if (length < 0x100) {
			out.write(0x81);
			out.write(length);
		}
		else {
			out.write(0x82);
			out.write(length >> 8);
			out.write(length);
		}

		out.writeBytes(value.toByteArray());

		return out.toByteArray();
	}

	private static byte[] ascii(String value) {
		return value.getBytes(StandardCharsets.US_ASCII);
	}

	private static String pem(String type, byte[] der) {
		String base64 = Base64.getMimeEncoder(64, new byte[] { '\n' }).encodeToString(der);

		return "-----BEGIN " + type + "-----\n" + base64 + "\n-----END " + type + "-----\n";
	}
}
