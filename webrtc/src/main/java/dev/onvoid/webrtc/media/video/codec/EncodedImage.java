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

package dev.onvoid.webrtc.media.video.codec;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * An encoded video frame: what a {@link VideoEncoder} produces and a {@link
 * VideoDecoder} consumes.
 * <p>
 * An encoder hands its images to WebRTC through {@link
 * VideoEncoder.Callback#onEncodedFrame(EncodedImage)}, which copies the
 * payload, so the buffer may be reused as soon as that call returns. The
 * {@link #getCaptureTimeNs() capture time} of such an image must be the
 * {@link dev.onvoid.webrtc.media.video.VideoFrame#timestampNs timestamp} of
 * the frame it encodes: that is how WebRTC matches the output to the input.
 * <p>
 * The images WebRTC hands to a decoder have a read-only buffer that is valid
 * only until {@link VideoDecoder#decode(EncodedImage)} returns; a decoder that
 * needs the data longer has to copy it. Their capture time identifies the
 * frame, and the decoder has to give its decoded frame the same timestamp.
 *
 * @author Alex Andres
 */
public final class EncodedImage {

	/**
	 * The type of an encoded frame.
	 */
	public enum FrameType {

		/** A frame without data, for example one the encoder dropped. */
		EMPTY(0),

		/** A key frame, which decodes without any other frame. */
		KEY(3),

		/** A delta frame, which depends on earlier frames. */
		DELTA(4);


		private final int nativeIndex;


		FrameType(int nativeIndex) {
			this.nativeIndex = nativeIndex;
		}

		int getNativeIndex() {
			return nativeIndex;
		}

		static FrameType fromNativeIndex(int nativeIndex) {
			for (FrameType type : values()) {
				if (type.nativeIndex == nativeIndex) {
					return type;
				}
			}

			throw new IllegalArgumentException("Unknown frame type: " + nativeIndex);
		}

	}


	private final ByteBuffer buffer;

	private final int encodedWidth;

	private final int encodedHeight;

	private final long captureTimeNs;

	private final FrameType frameType;

	private final int rotation;

	private final Integer qp;


	private EncodedImage(ByteBuffer buffer, int encodedWidth, int encodedHeight,
			long captureTimeNs, FrameType frameType, int rotation, Integer qp) {
		this.buffer = buffer;
		this.encodedWidth = encodedWidth;
		this.encodedHeight = encodedHeight;
		this.captureTimeNs = captureTimeNs;
		this.frameType = frameType;
		this.rotation = rotation;
		this.qp = qp;
	}

	/**
	 * Returns the encoded payload, from its position to its limit.
	 *
	 * @return The encoded data.
	 */
	public ByteBuffer getBuffer() {
		return buffer;
	}

	/**
	 * @return The width of the encoded frame in pixels.
	 */
	public int getEncodedWidth() {
		return encodedWidth;
	}

	/**
	 * @return The height of the encoded frame in pixels.
	 */
	public int getEncodedHeight() {
		return encodedHeight;
	}

	/**
	 * @return The capture time that identifies the frame, in nanoseconds.
	 */
	public long getCaptureTimeNs() {
		return captureTimeNs;
	}

	/**
	 * @return Whether this is a key frame, a delta frame or an empty one.
	 */
	public FrameType getFrameType() {
		return frameType;
	}

	/**
	 * @return The rotation of the frame in degrees, a multiple of 90.
	 */
	public int getRotation() {
		return rotation;
	}

	/**
	 * @return The quantizer the frame was encoded with, or {@code null} if it
	 *         is not known.
	 */
	public Integer getQp() {
		return qp;
	}

	@Override
	public String toString() {
		return String.format("%s@%d [size=%d, width=%d, height=%d, captureTimeNs=%d, frameType=%s, rotation=%d, qp=%s]",
				EncodedImage.class.getSimpleName(), hashCode(),
				buffer.remaining(), encodedWidth, encodedHeight,
				captureTimeNs, frameType, rotation, qp);
	}

	/**
	 * @return A new builder for an encoded image.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Wraps an image WebRTC hands to a decoder. Called from native code.
	 */
	@SuppressWarnings("unused")
	private static EncodedImage fromNative(ByteBuffer buffer, int encodedWidth,
			int encodedHeight, long captureTimeNs, int frameType, int rotation,
			int qp) {
		return new EncodedImage(buffer.asReadOnlyBuffer(), encodedWidth,
				encodedHeight, captureTimeNs, FrameType.fromNativeIndex(frameType),
				rotation, qp < 0 ? null : qp);
	}

	/**
	 * Returns the payload as a direct buffer whose capacity is its size, which
	 * native code can address. A heap buffer is copied. Called from native
	 * code.
	 */
	@SuppressWarnings("unused")
	private ByteBuffer getDirectPayload() {
		if (buffer.isDirect()) {
			return buffer.slice();
		}

		ByteBuffer direct = ByteBuffer.allocateDirect(buffer.remaining());
		direct.put(buffer.duplicate());
		direct.flip();

		return direct;
	}

	/**
	 * Returns the native index of the frame type. Called from native code.
	 */
	@SuppressWarnings("unused")
	private int getFrameTypeIndex() {
		return frameType.getNativeIndex();
	}

	/**
	 * Returns the quantizer, or -1 if it is not known. Called from native code.
	 */
	@SuppressWarnings("unused")
	private int getQpOrUnknown() {
		return qp == null ? -1 : qp;
	}



	/**
	 * Builds an {@link EncodedImage}, which is what a {@link VideoEncoder}
	 * does for each frame it encodes.
	 */
	public static final class Builder {

		private ByteBuffer buffer;

		private int encodedWidth;

		private int encodedHeight;

		private long captureTimeNs;

		private FrameType frameType = FrameType.DELTA;

		private int rotation;

		private Integer qp;


		private Builder() {
		}

		/**
		 * Sets the encoded payload, which is read from its position to its
		 * limit. Direct and heap buffers are both accepted.
		 *
		 * @param buffer The encoded data.
		 *
		 * @return This builder.
		 */
		public Builder setBuffer(ByteBuffer buffer) {
			this.buffer = buffer;
			return this;
		}

		/**
		 * @param width The width of the encoded frame in pixels.
		 *
		 * @return This builder.
		 */
		public Builder setEncodedWidth(int width) {
			this.encodedWidth = width;
			return this;
		}

		/**
		 * @param height The height of the encoded frame in pixels.
		 *
		 * @return This builder.
		 */
		public Builder setEncodedHeight(int height) {
			this.encodedHeight = height;
			return this;
		}

		/**
		 * Sets the capture time, which must be the timestamp of the frame
		 * that was encoded.
		 *
		 * @param captureTimeNs The capture time in nanoseconds.
		 *
		 * @return This builder.
		 */
		public Builder setCaptureTimeNs(long captureTimeNs) {
			this.captureTimeNs = captureTimeNs;
			return this;
		}

		/**
		 * @param frameType Whether this is a key frame or a delta frame.
		 *
		 * @return This builder.
		 */
		public Builder setFrameType(FrameType frameType) {
			this.frameType = frameType;
			return this;
		}

		/**
		 * @param rotation The rotation of the frame in degrees, a multiple of
		 *                 90.
		 *
		 * @return This builder.
		 */
		public Builder setRotation(int rotation) {
			this.rotation = rotation;
			return this;
		}

		/**
		 * Sets the quantizer. Without one, WebRTC parses it from the payload
		 * of VP8, VP9 and H.264 frames, which quality scaling depends on.
		 *
		 * @param qp The quantizer, or {@code null} if it is not known.
		 *
		 * @return This builder.
		 */
		public Builder setQp(Integer qp) {
			this.qp = qp;
			return this;
		}

		/**
		 * @return The encoded image.
		 *
		 * @throws NullPointerException     If the buffer or the frame type is
		 *                                  missing.
		 * @throws IllegalArgumentException If the rotation is not a multiple
		 *                                  of 90 or the size is negative.
		 */
		public EncodedImage build() {
			Objects.requireNonNull(buffer, "Buffer is null");
			Objects.requireNonNull(frameType, "Frame type is null");

			if (rotation % 90 != 0) {
				throw new IllegalArgumentException("Rotation must be a multiple of 90");
			}
			if (encodedWidth < 0 || encodedHeight < 0) {
				throw new IllegalArgumentException("Size must not be negative");
			}

			return new EncodedImage(buffer, encodedWidth, encodedHeight,
					captureTimeNs, frameType, rotation, qp);
		}

	}

}
