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

/**
 * An encoded video frame, as an {@link RTCEncodedFrameTransformer} sees it.
 *
 * @author Alex Andres
 */
public class RTCEncodedVideoFrame extends RTCEncodedFrame {

	private final boolean keyFrame;

	private final int width;

	private final int height;

	private final String rid;

	private final long frameId;

	private final int spatialIndex;

	private final int temporalIndex;


	/**
	 * Constructor to be used by the native api.
	 */
	RTCEncodedVideoFrame(long handle, int size, long timestamp, long ssrc,
			int payloadType, String mimeType, long captureTimeUs,
			boolean keyFrame, int width, int height, String rid, long frameId,
			int spatialIndex, int temporalIndex) {
		super(handle, size, timestamp, ssrc, payloadType, mimeType, captureTimeUs);

		this.keyFrame = keyFrame;
		this.width = width;
		this.height = height;
		this.rid = rid;
		this.frameId = frameId;
		this.spatialIndex = spatialIndex;
		this.temporalIndex = temporalIndex;
	}

	/**
	 * @return True if this frame can be decoded without any frame before it.
	 */
	public boolean isKeyFrame() {
		return keyFrame;
	}

	/**
	 * @return The frame width in pixels, or 0 if the codec does not say for
	 * this frame, which is common for frames other than key frames.
	 */
	public int getWidth() {
		return width;
	}

	/**
	 * @return The frame height in pixels, or 0 if the codec does not say for
	 * this frame, which is common for frames other than key frames.
	 */
	public int getHeight() {
		return height;
	}

	/**
	 * @return The RTP stream ID of the simulcast layer this frame belongs to,
	 * or {@code null} without simulcast.
	 */
	public String getRid() {
		return rid;
	}

	/**
	 * @return The frame ID from the dependency descriptor, or -1 if there is
	 * none.
	 */
	public long getFrameId() {
		return frameId;
	}

	/**
	 * @return The spatial layer of this frame, for scalable codecs.
	 */
	public int getSpatialIndex() {
		return spatialIndex;
	}

	/**
	 * @return The temporal layer of this frame, for scalable codecs.
	 */
	public int getTemporalIndex() {
		return temporalIndex;
	}

	@Override
	public String toString() {
		return String.format("%s@%d [mimeType=%s, keyFrame=%s, size=%d, %dx%d, timestamp=%d, ssrc=%d]",
				getClass().getSimpleName(), hashCode(), getMimeType(), keyFrame,
				getSize(), width, height, getTimestamp(), getSsrc());
	}

}
