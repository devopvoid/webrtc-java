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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a bidirectional data channel between two peers. An RTCDataChannel
 * is created via a factory method on an {@link RTCPeerConnection}.
 *
 * @author Alex Andres
 */
public class RTCDataChannel extends DisposableNativeObject {

	/**
	 * The native observer registered via {@link #registerObserver}. The
	 * RTCDataChannel does not take ownership of the Java observer, but it
	 * owns the native observer wrapper; it is freed when replaced, when
	 * {@link #unregisterObserver()} is called, and when this channel is
	 * disposed.
	 */
	@SuppressWarnings("unused")
	private long observerHandle;

	/**
	 * The threshold at which the buffered amount counts as low.
	 */
	private volatile long bufferedAmountLowThreshold;

	/**
	 * The buffered amount as the events of the channel tell it: raised by
	 * every message sent through this object, lowered by every decrease the
	 * observer hears of. Unlike the native value, read at once on the
	 * observer's thread, it is the amount at the time of each event.
	 */
	private final AtomicLong bufferedAmountTold = new AtomicLong();


	/**
	 * Used by the native api.
	 */
	private RTCDataChannel() {

	}

	/**
	 * Register an observer to receive events from this RTCDataChannel. The
	 * observer will replace the previously registered observer.
	 * <p>
	 * Calls from several threads through this object, also while it is
	 * disposed, are safe: the call made last wins, and one that meets a
	 * dispose has no effect. While another thread's call through this object
	 * is still handing an observer to WebRTC, this call may return before
	 * WebRTC has its observer; the other call hands it on before it returns.
	 *
	 * @param observer The new data channel observer.
	 */
	public void registerObserver(RTCDataChannelObserver observer) {
		registerNativeObserver(observer != null ? new ThresholdObserver(observer) : null);
	}

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
	 * Returns the priority of this RTCDataChannel, as set in {@link
	 * RTCDataChannelInit#priority}. A channel that is announced by the remote
	 * peer has the priority the remote peer gave it; a negotiated channel has
	 * the one it was created with on each side.
	 * A numeric priority that lies between two levels is reported as the
	 * nearest level above it.
	 *
	 * @return The priority of this data channel.
	 */
	public native RTCPriorityType getPriority();

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
	 * Sets the threshold at which the buffered amount counts as low. Once
	 * the buffered amount falls from above the threshold to the threshold or
	 * below, the observer is told through {@link
	 * RTCDataChannelObserver#onBufferedAmountLow()}. A sender that keeps the
	 * buffer filled between the threshold and a high mark of its own uses
	 * this to send more as soon as the channel has room.
	 * <p>
	 * WebRTC reports the buffered amount in steps rather than for every
	 * message sent, as it does for {@link
	 * RTCDataChannelObserver#onBufferedAmountChange(long)}. The observer may
	 * therefore hear of the low buffered amount only once it has fallen
	 * somewhat further. It always hears of it when the buffer is empty.
	 * <p>
	 * The threshold belongs to this object and is 0 until it is set.
	 *
	 * @param threshold The threshold in bytes.
	 *
	 * @throws IllegalArgumentException if the threshold is negative.
	 */
	public void setBufferedAmountLowThreshold(long threshold) {
		if (threshold < 0) {
			throw new IllegalArgumentException("The threshold must not be negative, got " + threshold);
		}

		bufferedAmountLowThreshold = threshold;
	}

	/**
	 * Returns the threshold at which the buffered amount counts as low.
	 *
	 * @return The threshold in bytes, 0 unless set.
	 *
	 * @see #setBufferedAmountLowThreshold(long)
	 */
	public long getBufferedAmountLowThreshold() {
		return bufferedAmountLowThreshold;
	}

	/**
	 * Closes this RTCDataChannel. It may be called regardless of whether the
	 * RTCDataChannel was created by this peer or the remote peer.
	 * <p>
	 * This does not dispose the channel, unlike the close method of other
	 * disposable objects: its observer still hears of the state change, and
	 * its state can still be read. Neither does a try-with-resources statement
	 * then, so {@link #dispose()} remains to be called.
	 */
	@Override
	public native void close();

	@Override
	public native void dispose();

	/**
	 * Sends data in the provided buffer to the remote peer. Only the bytes
	 * between the buffer's position and limit are sent, for heap and direct
	 * buffers alike. The caller's position is left untouched.
	 *
	 * @param buffer The buffer to be queued for transmission.
	 *
	 * @throws Exception If the native channel rejects the send, for example
	 *                   because it is not open or its send buffer is full.
	 */
	public void send(RTCDataChannelBuffer buffer) throws Exception {
		ByteBuffer data = buffer.data;
		long size = data.remaining();

		// Counted before WebRTC queues it, so that no decrease comes first.
		bufferedAmountTold.addAndGet(size);

		try {
			if (data.isDirect()) {
				if (data.position() == 0 && data.limit() == data.capacity()) {
					sendDirectBuffer(data, buffer.binary);
				}
				else {
					sendDirectBuffer(data.slice(), buffer.binary);
				}
			}
			else {
				sendByteArrayBuffer(copyWindow(data), buffer.binary);
			}
		}
		catch (Throwable e) {
			bufferedAmountTold.addAndGet(-size);
			throw e;
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
	static byte[] copyWindow(ByteBuffer data) {
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
		long size = countAsync(data.remaining());

		try {
			if (data.isDirect()) {
				sendDirectBufferAsync(data, data.position(), data.remaining(), buffer.binary);
			}
			else {
				sendByteArrayBufferAsync(copyWindow(data), buffer.binary);
			}
		}
		catch (Throwable e) {
			bufferedAmountTold.addAndGet(-size);
			throw e;
		}
	}

	private native void sendDirectBufferAsync(ByteBuffer buffer, int position, int length, boolean binary);

	private native void sendByteArrayBufferAsync(byte[] buffer, boolean binary);

	/**
	 * Sends data asynchronously and reports the native send result. Success
	 * means the local send operation accepted the message, not that the peer
	 * received it. The readable buffer window is copied before this method
	 * returns, without changing its position or limit.
	 * <p>
	 * The observer is called once when the operation completes or is discarded
	 * during channel shutdown. It runs on a native callback thread shared with
	 * other WebRTC observers, in the order the results arrive,
	 * and may run before this method returns. It may call other WebRTC methods,
	 * but should not block, since that holds up the other observers. No
	 * callback is guaranteed during JVM shutdown.
	 * <p>
	 * Invalid arguments and failures preparing the native operation are thrown
	 * on the calling thread. If preparation fails, the observer is not called.
	 * Exceptions thrown by the observer are printed and cleared on the callback
	 * thread; they do not propagate to the sender.
	 *
	 * @param buffer The buffer to be queued for transmission.
	 * @param observer The observer for this send operation.
	 * @throws NullPointerException If the buffer, its data, or the observer is null.
	 */
	public void sendAsync(RTCDataChannelBuffer buffer, RTCDataChannelSendObserver observer) {
		Objects.requireNonNull(observer, "observer");
		ByteBuffer data = Objects.requireNonNull(buffer.data, "buffer.data");
		long size = countAsync(data.remaining());

		try {
			if (data.isDirect()) {
				sendDirectBufferAsyncWithObserver(data, data.position(), data.remaining(), buffer.binary, observer);
			}
			else {
				sendByteArrayBufferAsyncWithObserver(copyWindow(data), buffer.binary, observer);
			}
		}
		catch (Throwable e) {
			bufferedAmountTold.addAndGet(-size);
			throw e;
		}
	}

	/**
	 * Counts a message sent asynchronously, which WebRTC queues only while the
	 * channel is open. One it refuses otherwise is never taken off again. A
	 * channel that closes meanwhile has no more decreases to tell.
	 *
	 * @return The bytes counted.
	 */
	private long countAsync(long size) {
		try {
			if (getState() != RTCDataChannelState.OPEN) {
				return 0;
			}
		}
		catch (IllegalStateException e) {
			// Disposed, which the send itself reports.
			return 0;
		}

		bufferedAmountTold.addAndGet(size);

		return size;
	}

	private native void sendDirectBufferAsyncWithObserver(ByteBuffer buffer, int position,
			int length, boolean binary, RTCDataChannelSendObserver observer);

	private native void sendByteArrayBufferAsyncWithObserver(byte[] buffer, boolean binary,
			RTCDataChannelSendObserver observer);

	private native void registerNativeObserver(RTCDataChannelObserver observer);


	/**
	 * Hands the events of the channel to the observer of the application,
	 * and tells it when the buffered amount falls to the threshold. It goes
	 * by the amount the events tell, as browsers do: the native one has
	 * often fallen further by the time an event reaches this thread, which
	 * would hide where the event crossed the threshold.
	 */
	private final class ThresholdObserver implements RTCDataChannelObserver {

		private final RTCDataChannelObserver observer;


		ThresholdObserver(RTCDataChannelObserver observer) {
			this.observer = observer;
		}

		@Override
		public void onBufferedAmountChange(long sentDataSize) {
			// Never below zero, e.g. for a message counted as refused that
			// WebRTC took after all.
			long after = bufferedAmountTold.accumulateAndGet(sentDataSize, (told, sent) -> Math.max(0, told - sent));
			long before = after + sentDataSize;
			long threshold = bufferedAmountLowThreshold;

			observer.onBufferedAmountChange(sentDataSize);

			if (before > threshold && after <= threshold) {
				observer.onBufferedAmountLow();
			}
		}

		@Override
		public void onStateChange() {
			observer.onStateChange();
		}

		@Override
		public void onMessage(RTCDataChannelBuffer buffer) {
			observer.onMessage(buffer);
		}

		@Override
		public void onBufferedAmountLow() {
			// Called only by this class itself.
		}
	}

}
