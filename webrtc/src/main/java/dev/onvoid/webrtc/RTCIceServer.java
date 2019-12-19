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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The RTCIceServer is used to describe the STUN and TURN servers that can be
 * used by the ICE Agent to establish a connection with a peer.
 *
 * @author Alex Andres
 */
public class RTCIceServer {

	/**
	 * List of STUN or TURN URI(s) associated with this server. The "host" part
	 * of the URI may contain either an IP address or a hostname. Valid formats
	 * are described in RFC7064 and RFC7065.
	 */
	public List<String> urls;

	/**
	 * The username to be used when the TURN server requests authorization.
	 */
	public String username;

	/**
	 * The password to be used when the TURN server requests authorization.
	 */
	public String password;

	/**
	 * The TLS certificate policy.
	 */
	public TlsCertPolicy tlsCertPolicy;

	/**
	 * If the URIs in {@link #urls} only contain IP addresses, this field can be
	 * used to indicate the hostname, which may be necessary for TLS (using the
	 * SNI extension). If {@link #urls} itself contains the hostname, this isn't
	 * necessary.
	 */
	public String hostname;

	/**
	 * List of protocols to be used in the TLS ALPN extension.
	 */
	public List<String> tlsAlpnProtocols;

	/**
	 * List of elliptic curves to be used in the TLS elliptic curves extension.
	 * Only curve names supported by OpenSSL should be used (eg.
	 * "P-256","X25519").
	 */
	public List<String> tlsEllipticCurves;


	/**
	 * Creates an instance of RTCIceServer.
	 */
	public RTCIceServer() {
		urls = new ArrayList<>();
		tlsAlpnProtocols = new ArrayList<>();
		tlsEllipticCurves = new ArrayList<>();
		tlsCertPolicy = TlsCertPolicy.SECURE;
	}

	@Override
	public int hashCode() {
		return Objects.hash(hostname, password, tlsAlpnProtocols, tlsCertPolicy,
				tlsEllipticCurves, urls, username);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		
		RTCIceServer other = (RTCIceServer) obj;
		
		return Objects.equals(hostname, other.hostname) &&
				Objects.equals(password, other.password) &&
				Objects.equals(tlsAlpnProtocols, other.tlsAlpnProtocols) &&
				tlsCertPolicy == other.tlsCertPolicy &&
				Objects.equals(tlsEllipticCurves, other.tlsEllipticCurves) &&
				Objects.equals(urls, other.urls) &&
				Objects.equals(username, other.username);
	}

	@Override
	public String toString() {
		return String.format("%s@%d [urls=%s, username=%s, password=%s, tlsCertPolicy=%s, hostname=%s, tlsAlpnProtocols=%s, tlsEllipticCurves=%s]",
						RTCIceServer.class.getSimpleName(), hashCode(),
						urls, username, password, tlsCertPolicy, hostname,
						tlsAlpnProtocols, tlsEllipticCurves);
	}
}
