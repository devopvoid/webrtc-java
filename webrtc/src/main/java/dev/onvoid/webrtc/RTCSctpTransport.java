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

import dev.onvoid.webrtc.internal.DisposableNativeObject;

/**
 * The SCTP transport that carries the data channels of a peer connection,
 * as {@link RTCPeerConnection#getSctpTransport()} returns it. It tells how
 * large a message the remote peer takes and how many data channels can be
 * open at once.
 * <p>
 * Each call of {@link RTCPeerConnection#getSctpTransport()} returns a new
 * instance, which holds a reference to the transport until it is disposed.
 *
 * @author Alex Andres
 */
public class RTCSctpTransport extends DisposableNativeObject {

	/**
	 * Used by the native api.
	 */
	private RTCSctpTransport() {

	}

	/**
	 * Returns the DTLS transport over which the SCTP packets are sent and
	 * received. The instance is the caller's to dispose.
	 *
	 * @return The DTLS transport, or {@code null} once the transport is
	 * closed.
	 */
	public native RTCDtlsTransport getTransport();

	/**
	 * @return The current state of the transport.
	 */
	public native RTCSctpTransportState getState();

	/**
	 * Returns the largest message in bytes that can be sent with {@link
	 * RTCDataChannel#send(RTCDataChannelBuffer)}, as the two peers agreed on
	 * in their session descriptions. A message larger than that is refused.
	 *
	 * @return The largest message size in bytes, or {@code null} while it is
	 * not known yet.
	 */
	public native Double getMaxMessageSize();

	/**
	 * Returns how many data channels can be open at the same time, as the
	 * SCTP association agreed on.
	 *
	 * @return The number of channels, or {@code null} before the transport
	 * is {@link RTCSctpTransportState#CONNECTED}.
	 */
	public native Integer getMaxChannels();

	/**
	 * Releases the reference this instance holds. The transport itself stays
	 * with its peer connection, as do other instances of it.
	 */
	@Override
	public native void dispose();

	/**
	 * Two instances are equal if they refer to the same transport, e.g. when
	 * obtained from two calls of {@link RTCPeerConnection#getSctpTransport()}.
	 * A disposed instance is never equal to anything but itself.
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof RTCSctpTransport)) {
			return false;
		}

		long handle = getNativeHandle();

		return handle != 0 && handle == ((RTCSctpTransport) obj).getNativeHandle();
	}

	@Override
	public int hashCode() {
		return nativeHashCode();
	}

}
