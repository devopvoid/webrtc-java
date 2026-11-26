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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the certificates that {@link RTCCertificatePEM#generate} makes.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCCertificateGenerateTests extends TestBase {

	@Test
	void ecdsaValidFor30Days() throws Exception {
		long now = System.currentTimeMillis();
		RTCCertificatePEM certificate = RTCCertificatePEM.generate(RTCCertificateKeyType.ECDSA);

		assertTrue(certificate.getCertificate().startsWith("-----BEGIN CERTIFICATE-----"));
		assertTrue(certificate.getPrivateKey().contains("PRIVATE KEY"), certificate.getPrivateKey());
		assertEquals("EC", x509(certificate).getPublicKey().getAlgorithm());

		assertExpires(certificate, now, Duration.ofDays(30));
	}

	@Test
	void rsaWithLifetime() throws Exception {
		long now = System.currentTimeMillis();
		RTCCertificatePEM certificate = RTCCertificatePEM.generate(RTCCertificateKeyType.RSA, Duration.ofDays(2));

		assertTrue(certificate.getPrivateKey().contains("PRIVATE KEY"), certificate.getPrivateKey());
		assertEquals("RSA", x509(certificate).getPublicKey().getAlgorithm());

		assertExpires(certificate, now, Duration.ofDays(2));
	}

	@Test
	void eachCertificateNew() {
		RTCCertificatePEM first = RTCCertificatePEM.generate(RTCCertificateKeyType.ECDSA);
		RTCCertificatePEM second = RTCCertificatePEM.generate(RTCCertificateKeyType.ECDSA);

		assertNotEquals(first.getPrivateKey(), second.getPrivateKey());
		assertNotEquals(first.getCertificate(), second.getCertificate());
	}

	@Test
	void lifetimeChecked() {
		assertThrows(IllegalArgumentException.class,
				() -> RTCCertificatePEM.generate(RTCCertificateKeyType.ECDSA, Duration.ZERO));
		assertThrows(IllegalArgumentException.class,
				() -> RTCCertificatePEM.generate(RTCCertificateKeyType.ECDSA, Duration.ofSeconds(-1)));
		assertThrows(IllegalArgumentException.class,
				() -> RTCCertificatePEM.generate(RTCCertificateKeyType.ECDSA,
						RTCCertificatePEM.MAX_LIFETIME.plusSeconds(1)));
		assertThrows(NullPointerException.class, () -> RTCCertificatePEM.generate(null));

		long now = System.currentTimeMillis();

		assertExpires(RTCCertificatePEM.generate(RTCCertificateKeyType.ECDSA, RTCCertificatePEM.MAX_LIFETIME),
				now, RTCCertificatePEM.MAX_LIFETIME);
	}

	@Test
	void certificateKeepsFingerprint() throws Exception {
		RTCCertificatePEM certificate = RTCCertificatePEM.generate(RTCCertificateKeyType.ECDSA);

		String first = fingerprint(certificate);
		String second = fingerprint(certificate);

		assertEquals(first, second);
		// A connection without one makes a certificate of its own.
		assertNotEquals(first, fingerprint(null));
	}

	@Test
	void certificateReadBack() {
		RTCCertificatePEM certificate = RTCCertificatePEM.generate(RTCCertificateKeyType.ECDSA);
		RTCConfiguration config = new RTCConfiguration();
		config.certificates.add(certificate);

		RTCPeerConnection connection = factory.createPeerConnection(config, candidate -> { });

		try {
			List<RTCCertificatePEM> certificates = connection.getConfiguration().certificates;

			assertEquals(1, certificates.size());
			assertEquals(certificate.getCertificate(), certificates.get(0).getCertificate());
			assertEquals(certificate.getExpires(), certificates.get(0).getExpires());
		}
		finally {
			connection.close();
		}
	}

	/** The DTLS fingerprint in the offer of a connection with the certificate. */
	private String fingerprint(RTCCertificatePEM certificate) throws Exception {
		RTCConfiguration config = new RTCConfiguration();

		if (certificate != null) {
			config.certificates.add(certificate);
		}

		RTCPeerConnection connection = factory.createPeerConnection(config, candidate -> { });

		try {
			RTCDataChannel channel = connection.createDataChannel("data", new RTCDataChannelInit());
			TestCreateDescObserver offer = new TestCreateDescObserver();

			connection.createOffer(new RTCOfferOptions(), offer);

			String sdp = offer.get(5, TimeUnit.SECONDS).sdp;
			channel.dispose();

			for (String line : sdp.split("\r\n")) {
				if (line.startsWith("a=fingerprint:")) {
					return line;
				}
			}

			throw new AssertionError("No fingerprint in " + sdp);
		}
		finally {
			connection.close();
		}
	}

	private static X509Certificate x509(RTCCertificatePEM certificate) throws Exception {
		byte[] pem = certificate.getCertificate().getBytes(StandardCharsets.US_ASCII);

		return (X509Certificate) CertificateFactory.getInstance("X.509")
				.generateCertificate(new ByteArrayInputStream(pem));
	}

	private static void assertExpires(RTCCertificatePEM certificate, long now, Duration lifetime) {
		long expires = certificate.getExpires();
		long expected = now + lifetime.toMillis();

		// Read from the certificate in whole seconds.
		assertTrue(Math.abs(expires - expected) < TimeUnit.MINUTES.toMillis(1),
				"Expires " + expires + ", expected about " + expected);
	}

}
