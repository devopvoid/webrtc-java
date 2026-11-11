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
 * Represents a certificate used to authenticate WebRTC communications. This
 * class contains PEM strings of an RTCCertificate's private key and
 * certificate.
 *
 * @author Alex Andres
 */
public class RTCCertificatePEM {

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
}
