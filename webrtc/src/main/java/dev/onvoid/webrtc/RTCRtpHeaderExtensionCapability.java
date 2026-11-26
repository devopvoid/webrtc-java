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

import java.util.Objects;

/**
 * Describes an RTP header extension: one that WebRTC supports, as {@link
 * RTCRtpCapabilities#getHeaderExtensions()} lists them, or one of a
 * transceiver, with the direction it is to be negotiated in.
 *
 * @author Alex Andres
 *
 * @see RTCRtpTransceiver#setHeaderExtensionsToNegotiate(java.util.List)
 */
public class RTCRtpHeaderExtensionCapability {

	private final String uri;

	private final RTCRtpTransceiverDirection direction;


	/**
	 * Creates a header extension to be negotiated in both directions.
	 *
	 * @param uri The URI of the header extension.
	 */
	public RTCRtpHeaderExtensionCapability(String uri) {
		this(uri, RTCRtpTransceiverDirection.SEND_RECV);
	}

	/**
	 * Creates a header extension to be negotiated in the given direction.
	 * {@link RTCRtpTransceiverDirection#STOPPED} keeps it out of the
	 * negotiation.
	 *
	 * @param uri       The URI of the header extension.
	 * @param direction The direction to negotiate it in.
	 */
	public RTCRtpHeaderExtensionCapability(String uri, RTCRtpTransceiverDirection direction) {
		this.uri = requireNonNull(uri, "uri");
		this.direction = requireNonNull(direction, "direction");
	}

	/**
	 * @return The URI of the header extension, e.g. {@code
	 * urn:ietf:params:rtp-hdrext:sdes:mid}.
	 */
	public String getUri() {
		return uri;
	}

	/**
	 * @return The direction the header extension is negotiated in, or {@link
	 * RTCRtpTransceiverDirection#STOPPED} if it is not negotiated.
	 */
	public RTCRtpTransceiverDirection getDirection() {
		return direction;
	}

	/**
	 * Returns the same header extension with another direction, e.g. to stop
	 * one in the list of {@link
	 * RTCRtpTransceiver#getHeaderExtensionsToNegotiate()}.
	 *
	 * @param direction The direction to negotiate it in.
	 *
	 * @return The header extension with that direction.
	 */
	public RTCRtpHeaderExtensionCapability withDirection(RTCRtpTransceiverDirection direction) {
		return new RTCRtpHeaderExtensionCapability(uri, direction);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof RTCRtpHeaderExtensionCapability)) {
			return false;
		}

		RTCRtpHeaderExtensionCapability other = (RTCRtpHeaderExtensionCapability) obj;

		return uri.equals(other.uri) && direction == other.direction;
	}

	@Override
	public int hashCode() {
		return Objects.hash(uri, direction);
	}

	@Override
	public String toString() {
		return String.format("%s [uri=%s, direction=%s]",
				RTCRtpHeaderExtensionCapability.class.getSimpleName(), uri, direction);
	}
}
