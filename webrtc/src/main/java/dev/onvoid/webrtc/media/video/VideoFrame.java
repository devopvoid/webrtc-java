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

public class VideoFrame implements RefCounted {
	
	/**
	 * The underlying frame buffer.
	 */
	public final VideoFrameBuffer buffer;

	/**
	 * Rotation of the frame in degrees.
	 */
	public final int rotation;

	/**
	 * Timestamp of the frame in nano seconds.
	 */
	public final long timestampNs;


	private VideoFrame(VideoFrameBuffer buffer, int rotation, long timestampNs) {
		if (buffer == null) {
			throw new IllegalArgumentException("VideoFrameBuffer must not be null");
		}
		if (rotation % 90 != 0) {
			throw new IllegalArgumentException("Rotation must be a multiple of 90");
		}

		this.buffer = buffer;
		this.rotation = rotation;
		this.timestampNs = timestampNs;
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
