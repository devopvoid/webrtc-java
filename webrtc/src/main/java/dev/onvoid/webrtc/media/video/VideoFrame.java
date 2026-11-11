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

package dev.onvoid.webrtc.media.video;

import dev.onvoid.webrtc.internal.RefCounted;

/**
 * Represents a video frame with an underlying buffer, rotation information, and timestamp.
 * <p>
 * A VideoFrame holds reference to its buffer data and implements the RefCounted
 * interface to manage the lifecycle of native resources through reference counting.
 * The frame includes a timestamp in nanoseconds for synchronization purposes.
 *
 * @author Alex Andres
 */
public class VideoFrame implements RefCounted {

	/** The underlying frame buffer. */
	public final VideoFrameBuffer buffer;

	/** Clockwise rotation of the frame in degrees: 0, 90, 180 or 270. */
	public final int rotation;

	/** Timestamp of the frame in nanoseconds. */
	public final long timestampNs;


	/**
	 * Creates a new VideoFrame with the specified buffer, rotation and timestamp.
	 *
	 * @param buffer      The video frame buffer containing the actual frame data.
	 * @param timestampNs The timestamp of the frame in nanoseconds.
	 *
	 * @throws IllegalArgumentException If buffer is null.
	 */
	public VideoFrame(VideoFrameBuffer buffer, long timestampNs) {
		this(buffer, 0, timestampNs);
	}

	/**
	 * Creates a new VideoFrame with the specified buffer, rotation and timestamp.
	 *
	 * @param buffer      The video frame buffer containing the actual frame data.
	 * @param rotation    The clockwise rotation of the frame in degrees (must be a multiple of 90).
	 *                    Any multiple is taken, and kept as its equal in 0, 90, 180 or 270, so that
	 *                    -90 and 450 are stored as 270 and 90.
	 * @param timestampNs The timestamp of the frame in nanoseconds.
	 *
	 * @throws IllegalArgumentException If buffer is null or rotation is not a multiple of 90.
	 */
	public VideoFrame(VideoFrameBuffer buffer, int rotation, long timestampNs) {
		if (buffer == null) {
			throw new IllegalArgumentException("VideoFrameBuffer must not be null");
		}
		if (rotation % 90 != 0) {
			throw new IllegalArgumentException("Rotation must be a multiple of 90");
		}

		this.buffer = buffer;
		this.rotation = ((rotation % 360) + 360) % 360;
		this.timestampNs = timestampNs;
	}

	/**
	 * Creates a deep copy of this VideoFrame. The new frame will have its own copy of the buffer data,
	 * in a {@link NativeI420Buffer} made from what {@link VideoFrameBuffer#toI420()} of the buffer
	 * returns.
	 *
	 * @return A new VideoFrame with a copy of the buffer data.
	 *
	 * @throws RuntimeException if the buffer has no readable pixels, such as a released buffer or one
	 *                          with heap byte buffers, or if the size, strides and planes of the I420
	 *                          buffer do not fit together.
	 */
	public VideoFrame copy() {
		return new VideoFrame(NativeI420Buffer.copy(buffer), rotation, timestampNs);
	}

	@Override
	public void retain() {
		buffer.retain();
	}

	@Override
	public void release() {
		buffer.release();
	}

	@Override
	public String toString() {
		return String.format("%s@%d [buffer=%s, rotation=%s, timestampNs=%s]",
				VideoFrame.class.getSimpleName(), hashCode(),
				buffer, rotation, timestampNs);
	}

}
