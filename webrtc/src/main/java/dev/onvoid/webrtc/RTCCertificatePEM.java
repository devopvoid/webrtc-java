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

import static java.util.Objects.requireNonNull;

import java.time.Duration;

import dev.onvoid.webrtc.internal.NativeLoader;

/**
 * Represents a certificate used to authenticate WebRTC communications. This
 * class contains PEM strings of an RTCCertificate's private key and
 * certificate.
 *
 * @author Alex Andres
 */
public class RTCCertificatePEM {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}

	/**
	 * The longest lifetime of a certificate made by {@link
	 * #generate(RTCCertificateKeyType, Duration)}, as WebRTC limits it.
	 */
	public static final Duration MAX_LIFETIME = Duration.ofDays(365);

	/**
	 * The value of {@link #getExpires()} for a certificate whose expiration
	 * time cannot be read. No actual expiration time has this value: those
	 * are whole seconds, multiples of 1000 in milliseconds.
	 */
	public static final long EXPIRES_UNKNOWN = -1;

	/**
	 * Indicates the time in milliseconds relative to the Unix epoch after which
	 * the certificate will be considered invalid, or {@link #EXPIRES_UNKNOWN}.
	 */
	private final long expires;

	/**
	 * PEM string representation of the private key.
	 */
	private final String privateKey;

	/**
	 * PEM string representation of the certificate.
	 */
	private final String certificate;


	/**
	 * Creates a new RTCCertificatePEM instance with the specified PEM string
	 * representation of the private key and certificate.
	 *
	 * @param privateKey  Private key as PEM string.
	 * @param certificate Certificate as PEM string.
	 * @param expires     The expiration time of this certificate in
	 *                    milliseconds relative to the Unix epoch, or
	 *                    {@link #EXPIRES_UNKNOWN}. It is informational only:
	 *                    WebRTC reads the expiration time from the PEM
	 *                    certificate itself.
	 */
	public RTCCertificatePEM(String privateKey, String certificate, long expires) {
		this.privateKey = privateKey;
		this.certificate = certificate;
		this.expires = expires;
	}

	/**
	 * @return The PEM string representation of the private key.
	 */
	public String getPrivateKey() {
		return privateKey;
	}

	/**
	 * @return The PEM string representation of the certificate.
	 */
	public String getCertificate() {
		return certificate;
	}

	/**
	 * @return The expiration time in milliseconds of this certificate relative
	 * to the Unix epoch, or {@link #EXPIRES_UNKNOWN} if it cannot be read from
	 * the certificate. Certificates returned by the native API, such as those of
	 * {@link RTCDtlsTransport#getRemoteCertificates()} and
	 * {@link RTCPeerConnection#getConfiguration()}, carry the time read from
	 * the certificate.
	 */
	public long getExpires() {
		return expires;
	}

	/**
	 * Generates a new self-signed certificate with a new private key, as
	 * {@code RTCPeerConnection.generateCertificate} of the WebRTC
	 * specification does, valid for 30 days.
	 *
	 * @param keyType The kind of key to make.
	 *
	 * @return The new certificate.
	 *
	 * @see #generate(RTCCertificateKeyType, Duration)
	 */
	public static RTCCertificatePEM generate(RTCCertificateKeyType keyType) {
		requireNonNull(keyType, "keyType");

		return generateCertificate(keyType.ordinal(), 0);
	}

	/**
	 * Generates a new self-signed certificate with a new private key, as
	 * {@code RTCPeerConnection.generateCertificate} of the WebRTC
	 * specification does.
	 * <p>
	 * A peer connection makes a certificate of its own unless {@link
	 * RTCConfiguration#certificates} has one. Its DTLS fingerprint, which the
	 * session description carries, therefore changes with every connection.
	 * An application that keeps a certificate, e.g. in a key store, and
	 * passes it to every connection keeps the fingerprint, so that a remote
	 * peer can recognize it again.
	 * <p>
	 * The key is made on the calling thread. An ECDSA key takes milliseconds,
	 * an RSA key can take far longer.
	 *
	 * @param keyType  The kind of key to make.
	 * @param lifetime How long the certificate is valid from now, at most
	 *                 {@link #MAX_LIFETIME}.
	 *
	 * @return The new certificate, with its private key.
	 *
	 * @throws IllegalArgumentException if the lifetime is not positive or
	 *                                  longer than {@link #MAX_LIFETIME}.
	 * @throws IllegalStateException    if the certificate could not be made.
	 */
	public static RTCCertificatePEM generate(RTCCertificateKeyType keyType, Duration lifetime) {
		requireNonNull(keyType, "keyType");
		requireNonNull(lifetime, "lifetime");

		if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0) {
			throw new IllegalArgumentException("The lifetime must be positive and at most "
					+ MAX_LIFETIME.toDays() + " days, got " + lifetime);
		}

		// At least a millisecond, which 0 would not be.
		return generateCertificate(keyType.ordinal(), Math.max(1, lifetime.toMillis()));
	}

	/*
	 * Generates a certificate of the RTCCertificateKeyType with the ordinal,
	 * valid for the given milliseconds, or the default lifetime for 0.
	 */
	private static native RTCCertificatePEM generateCertificate(int keyType, long lifetimeMillis);
}
