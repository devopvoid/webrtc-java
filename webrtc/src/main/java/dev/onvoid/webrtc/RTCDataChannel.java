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
 * Represents a bidirectional data channel between two peers. An RTCDataChannel
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
	 * Sends data in the provided buffer to the remote peer. Only the bytes
	 * between the buffer's position and limit are sent, for heap and direct
	 * buffers alike. The buffer is read through a duplicate, so the caller's
	 * position is left untouched.
	 *
	 * @param buffer The buffer to be queued for transmission.
	 *
	 * @throws Exception If queuing data is not possible because not enough
	 *                   buffer space is available.
	 */
	public void send(RTCDataChannelBuffer buffer) throws Exception {
		ByteBuffer data = buffer.data;

		if (data.isDirect()) {
			if (data.position() == 0 && data.limit() == data.capacity()) {
				sendDirectBuffer(data, buffer.binary);
			}
			else {
				ByteBuffer window = ByteBuffer.allocateDirect(data.remaining());
				window.put(data.duplicate());
				window.flip();
				sendDirectBuffer(window, buffer.binary);
			}
		}
		else {
			sendByteArrayBuffer(copyWindow(data), buffer.binary);
		}
	}

	/**
	 * Copies the readable window of a heap buffer, position to limit, into a
	 * fresh array for the byte array send path, which transmits whole arrays.
	 * The backing array is handed over directly only when the window covers
	 * it exactly; bytes outside the window (a nonzero position, a short
	 * limit, an array offset) must never reach the wire. Reads through a
	 * duplicate, so the caller's position is left untouched.
	 */
	private static byte[] copyWindow(ByteBuffer data) {
		if (data.hasArray() && data.arrayOffset() == 0 && data.position() == 0
				&& data.remaining() == data.array().length) {
			return data.array();
		}
		byte[] window = new byte[data.remaining()];
		data.duplicate().get(window);
		return window;
	}

	private native void sendDirectBuffer(ByteBuffer buffer, boolean binary);

	private native void sendByteArrayBuffer(byte[] buffer, boolean binary);

	/**
	 * Sends data in the provided buffer to the remote peer without blocking
	 * the calling thread on the native network thread, unlike
	 * {@link #send(RTCDataChannelBuffer)} whose call is marshalled
	 * synchronously. The data is copied out of the buffer before this method
	 * returns, so the buffer may be reused immediately; only the bytes
	 * between position and limit are sent.
	 *
	 * Errors are reported asynchronously: queueing failures are logged
	 * natively, and fatal errors close the data channel, which the registered
	 * {@link RTCDataChannelObserver} sees as a state change.
	 *
	 * @param buffer The buffer to be queued for transmission.
	 */
	public void sendAsync(RTCDataChannelBuffer buffer) {
		ByteBuffer data = buffer.data;

		if (data.isDirect()) {
			sendDirectBufferAsync(data, data.position(), data.remaining(), buffer.binary);
		}
		else {
			// The byte array path transmits whole arrays, so copy exactly
			// the readable window, position to limit; a duplicate leaves
			// the caller's position untouched.
			byte[] window = new byte[data.remaining()];
			data.duplicate().get(window);
			sendByteArrayBufferAsync(window, buffer.binary);
		}
	}

	private native void sendDirectBufferAsync(ByteBuffer buffer, int position, int length, boolean binary);

	private native void sendByteArrayBufferAsync(byte[] buffer, boolean binary);

}
