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

import dev.onvoid.webrtc.internal.DisposableNativeObject;

import java.nio.ByteBuffer;

/**
 * Represents a bi-directional data channel between two peers. An RTCDataChannel
 * is created via a factory method on an {@link RTCPeerConnection}.
 *
 * @author Alex Andres
 */
public class RTCDataChannel extends DisposableNativeObject {

	/**
	 * Used by the native api.
	 */
	private RTCDataChannel() {

	}

	/**
	 * Register an observer to receive events from this RTCDataChannel. The
	 * observer will replace the previously registered observer.
	 *
	 * @param observer The new data channel observer.
	 */
	public native void registerObserver(RTCDataChannelObserver observer);

	/**
	 * Unregister the last set RTCDataChannelObserver.
	 */
	public native void unregisterObserver();

	/**
	 * Returns the label that can be used to distinguish this RTCDataChannel
	 * object from other RTCDataChannel objects.
	 *
	 * @return The data channel label.
	 */
	public native String getLabel();

	/**
	 * Indicates whether the data channel is configured to use reliable
	 * transmission.
	 *
	 * @return true if the transmission is reliable, false otherwise.
	 */
	public native boolean isReliable();

	/**
	 * Returns true if the RTCDataChannel is ordered, and false if out of order
	 * delivery is allowed.
	 *
	 * @return true if message delivery is ordered, false otherwise.
	 */
	public native boolean isOrdered();

	/**
	 * Returns the length of the time window (in milliseconds) during which
	 * transmissions and retransmissions may occur in unreliable mode.
	 *
	 * @return The maximum life-time of packets in unreliable mode.
	 */
	public native int getMaxPacketLifeTime();

	/**
	 * Returns the maximum number of retransmissions that are attempted in
	 * unreliable mode.
	 *
	 * @return The maximum number of retransmissions.
	 */
	public native int getMaxRetransmits();

	/**
	 * Returns the name of the sub-protocol used with this RTCDataChannel.
	 *
	 * @return The name of the sub-protocol used.
	 */
	public native String getProtocol();

	/**
	 * Returns true if this RTCDataChannel was negotiated by the application, or
	 * false otherwise.
	 *
	 * @return true if the channel was negotiated by the application, false
	 * otherwise.
	 */
	public native boolean isNegotiated();

	/**
	 * Returns the ID for this RTCDataChannel. The value is initially {@code
	 * null}, which is what will be returned if the ID was not provided at
	 * channel creation time, and the DTLS role of the SCTP transport has not
	 * yet been negotiated. Otherwise, it will return the ID that was selected
	 * by the application. After the ID is set to a non-null value, it will not
	 * change.
	 *
	 * @return the ID for this data channel.
	 */
	public native int getId();

	/**
	 * Returns the state of this RTCDataChannel object.
	 *
	 * @return The current state of the data channel.
	 */
	public native RTCDataChannelState getState();

	/**
	 * Returns the number of bytes of application data (UTF-8 text and binary
	 * data) that have been queued using {@link #send(RTCDataChannelBuffer)}.
	 * The value does not include framing overhead incurred by the protocol, or
	 * buffering done by the operating system or network hardware.
	 *
	 * @return The number of bytes queued for transmission.
	 */
	public native long getBufferedAmount();

	/**
	 * Closes this RTCDataChannel. It may be called regardless of whether the
	 * RTCDataChannel was created by this peer or the remote peer.
	 */
	public native void close();

	@Override
	public native void dispose();

	/**
	 * Sends data in the provided buffer to the remote peer.
	 *
	 * @param buffer The buffer to be queued for transmission.
	 *
	 * @throws Exception If queuing data is not possible because not enough
	 *                   buffer space is available.
	 */
	public void send(RTCDataChannelBuffer buffer) throws Exception {
		ByteBuffer data = buffer.data;

		if (data.isDirect()) {
			sendDirectBuffer(data, buffer.binary);
		}
		else {
			byte[] arrayBuffer;

			if (data.hasArray()) {
				arrayBuffer = data.array();
			}
			else {
				arrayBuffer = new byte[data.remaining()];
				data.get(arrayBuffer);
			}

			sendByteArrayBuffer(arrayBuffer, buffer.binary);
		}
	}

	private native void sendDirectBuffer(ByteBuffer buffer, boolean binary);

	private native void sendByteArrayBuffer(byte[] buffer, boolean binary);

}
