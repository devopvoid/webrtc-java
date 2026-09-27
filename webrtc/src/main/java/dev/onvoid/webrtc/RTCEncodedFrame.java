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

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * One encoded audio or video frame, as an {@link RTCEncodedFrameTransformer}
 * sees it: the encoded payload and what WebRTC knows about it.
 * <p>
 * A frame is backed by the native frame WebRTC is about to send or decode,
 * and is valid only while the transform it was handed to runs, and only on
 * that transform's thread. Reading or changing the payload after that throws
 * an {@link IllegalStateException}; the metadata stays readable.
 * <p>
 * The payload is not copied into Java unless asked for: a transform that only
 * looks at the metadata, or that {@link #setData(ByteBuffer) replaces} the
 * payload without reading it, costs no copy at all.
 *
 * @author Alex Andres
 *
 * @see RTCEncodedVideoFrame
 * @see RTCEncodedAudioFrame
 */
public abstract class RTCEncodedFrame {

	/**
	 * Where the payload is copied to. There is one per transform thread, which
	 * grows to the largest frame seen and is reused for every frame after, so
	 * reading the payload allocates nothing.
	 */
	private static final ThreadLocal<ByteBuffer> SCRATCH = new ThreadLocal<>();

	/** The smallest scratch buffer, enough for any audio frame. */
	private static final int MIN_SCRATCH_CAPACITY = 4096;

	private final Thread owner;

	private final long timestamp;

	private final long ssrc;

	private final int payloadType;

	private final String mimeType;

	private final long captureTimeUs;

	/** The native frame, or 0 once the transform has returned. */
	private long handle;

	private int size;

	/** A copy of the current payload, or null if none was made yet. */
	private ByteBuffer data;

	private boolean dropped;


	RTCEncodedFrame(long handle, int size, long timestamp, long ssrc,
			int payloadType, String mimeType, long captureTimeUs) {
		this.owner = Thread.currentThread();
		this.handle = handle;
		this.size = size;
		this.timestamp = timestamp;
		this.ssrc = ssrc;
		this.payloadType = payloadType;
		this.mimeType = mimeType;
		this.captureTimeUs = captureTimeUs;
	}

	/**
	 * Returns the encoded payload, from position zero to its size.
	 * <p>
	 * The buffer holds a copy: changing it changes nothing until it is passed
	 * to {@link #setData(ByteBuffer)}. Its memory is reused for the frames
	 * that follow, so it must not be kept beyond the transform; copy what is
	 * needed later.
	 *
	 * @return The payload of this frame.
	 *
	 * @throws IllegalStateException If the transform has returned, or if
	 *                               called from another thread.
	 */
	public ByteBuffer getData() {
		checkAccess();

		if (data == null) {
			ByteBuffer scratch = scratch(size);

			if (size > 0) {
				copyData(handle, scratch);
			}

			data = scratch;
		}

		ByteBuffer view = data.duplicate();
		view.clear();
		view.limit(size);

		return view;
	}

	/**
	 * Replaces the payload with the remaining bytes of the given buffer. The
	 * bytes are copied, and the buffer's position is left unchanged.
	 *
	 * @param data The new payload.
	 *
	 * @throws IllegalStateException If the transform has returned, or if
	 *                               called from another thread.
	 */
	public void setData(ByteBuffer data) {
		Objects.requireNonNull(data, "Data is null");
		checkAccess();

		int length = data.remaining();

		if (data.isDirect()) {
			setDataBuffer(handle, data, data.position(), length);
		}
		else if (data.hasArray()) {
			setDataArray(handle, data.array(), data.arrayOffset() + data.position(), length);
		}
		else {
			// A read-only heap buffer exposes no array.
			byte[] copy = new byte[length];
			data.duplicate().get(copy);

			setDataArray(handle, copy, 0, length);
		}

		payloadChanged(length);
	}

	/**
	 * Replaces the payload with the given bytes, which are copied.
	 *
	 * @param data The new payload.
	 *
	 * @throws IllegalStateException If the transform has returned, or if
	 *                               called from another thread.
	 */
	public void setData(byte[] data) {
		Objects.requireNonNull(data, "Data is null");

		setData(data, 0, data.length);
	}

	/**
	 * Replaces the payload with a range of the given bytes, which are copied.
	 *
	 * @param data   The array holding the new payload.
	 * @param offset Where the payload starts in the array.
	 * @param length The payload size in bytes.
	 *
	 * @throws IndexOutOfBoundsException If the range lies outside the array.
	 * @throws IllegalStateException     If the transform has returned, or if
	 *                                   called from another thread.
	 */
	public void setData(byte[] data, int offset, int length) {
		Objects.requireNonNull(data, "Data is null");

		if (offset < 0 || length < 0 || offset > data.length - length) {
			throw new IndexOutOfBoundsException(String.format(
					"Range [%d, %d) out of bounds for length %d",
					offset, offset + length, data.length));
		}

		checkAccess();

		setDataArray(handle, data, offset, length);

		payloadChanged(length);
	}

	/**
	 * Drops this frame: it is released instead of being sent on. A receiver
	 * that misses a video frame cannot decode what depends on it, so dropping
	 * video frames usually means waiting for the next key frame.
	 *
	 * @throws IllegalStateException If the transform has returned, or if
	 *                               called from another thread.
	 */
	public void drop() {
		checkAccess();

		dropped = true;
	}

	/**
	 * @return True if this frame was {@link #drop() dropped}.
	 */
	public boolean isDropped() {
		return dropped;
	}

	/**
	 * @return The current payload size in bytes.
	 */
	public int getSize() {
		return size;
	}

	/**
	 * @return The RTP timestamp of this frame, an unsigned 32-bit value in
	 * the clock rate of the codec.
	 */
	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * @return The SSRC of the RTP stream this frame belongs to, an unsigned
	 * 32-bit value.
	 */
	public long getSsrc() {
		return ssrc;
	}

	/**
	 * @return The RTP payload type of this frame.
	 */
	public int getPayloadType() {
		return payloadType;
	}

	/**
	 * @return The codec of this frame as a MIME type, e.g. {@code video/VP8}
	 * or {@code audio/opus}.
	 */
	public String getMimeType() {
		return mimeType;
	}

	/**
	 * Returns the time the frame was captured, in microseconds. A sender's
	 * frames carry the local capture time. A receiver's carry the capture
	 * time of the remote capturer, relative to the NTP epoch, and only if the
	 * absolute capture time header extension is in use.
	 *
	 * @return The capture time in microseconds, or -1 if unknown.
	 */
	public long getCaptureTimeUs() {
		return captureTimeUs;
	}

	/**
	 * Runs the transform on the frame, on behalf of native code, and says
	 * whether the frame is to be sent on. Whatever happens, the frame is no
	 * longer usable afterwards, which is what makes it safe for native code to
	 * move on with the native frame.
	 */
	static boolean dispatch(RTCEncodedFrameTransformer transformer, RTCEncodedFrame frame) {
		try {
			transformer.transform(frame);

			return !frame.dropped;
		}
		catch (Throwable e) {
			// Reported the way an exception on any other thread would be,
			// which honors whatever handler the application installed.
			Thread thread = Thread.currentThread();

			try {
				thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
			}
			catch (Throwable ignored) {
				// Nothing more can be done about it here.
			}

			return false;
		}
		finally {
			frame.handle = 0;
			frame.data = null;
		}
	}

	private void payloadChanged(int length) {
		size = length;
		data = null;
	}

	private void checkAccess() {
		if (handle == 0) {
			throw new IllegalStateException(
					"An encoded frame is only valid while its transform runs");
		}
		if (Thread.currentThread() != owner) {
			throw new IllegalStateException(
					"An encoded frame is only valid on the thread running its transform");
		}
	}

	private static ByteBuffer scratch(int size) {
		ByteBuffer scratch = SCRATCH.get();

		if (scratch == null || scratch.capacity() < size) {
			int capacity = MIN_SCRATCH_CAPACITY;

			if (scratch != null) {
				capacity = Math.max(capacity, scratch.capacity());
			}
			while (capacity < size) {
				capacity = capacity > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : capacity * 2;
			}

			scratch = ByteBuffer.allocateDirect(capacity);

			SCRATCH.set(scratch);
		}

		return scratch;
	}

	private static native void copyData(long handle, ByteBuffer target);

	private static native void setDataBuffer(long handle, ByteBuffer source, int offset, int length);

	private static native void setDataArray(long handle, byte[] source, int offset, int length);

}
