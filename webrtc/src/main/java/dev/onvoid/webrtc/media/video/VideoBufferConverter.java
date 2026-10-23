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

import java.nio.ByteBuffer;

import dev.onvoid.webrtc.internal.NativeLoader;
import dev.onvoid.webrtc.media.FourCC;

/**
 * Utility methods to convert between I420 video frame buffers and other pixel
 * formats represented by a FourCC. Delegates actual conversion work to native
 * methods for performance.
 * <p>
 * A frame is stored without row padding, except where a format groups pixels
 * in pairs: a {@link FourCC#YUY2} or {@link FourCC#UYVY} row, and an
 * {@link FourCC#NV12} or {@link FourCC#NV21} chroma row, always ends with a
 * whole pair. With an odd width, a YUY2 or UYVY row takes
 * {@code 4 * ((width + 1) / 2)} bytes, and an NV12 or NV21 chroma row
 * {@code 2 * ((width + 1) / 2)}. Conversions in both directions use this
 * layout, and fail when the buffer is smaller than the frame.
 *
 * @author Alex Andres
 */
public final class VideoBufferConverter {

	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}

	/**
	 * Convert an arbitrary {@link VideoFrameBuffer} to the specified pixel format
	 * (given by {@code fourCC}) and write the result into a destination byte array.
	 * <p>
	 * The source buffer is first converted (if necessary) to an intermediate
	 * I420 layout via {@link VideoFrameBuffer#toI420()} and then transformed.
	 *
	 * @param src    Source video frame buffer (will be converted to I420 if not already).
	 * @param dst    Destination byte array expected to be large enough to hold the converted frame.
	 * @param fourCC Target FourCC format identifier.
	 *
	 * @throws NullPointerException if {@code src} or {@code dst} is {@code null}.
	 * @throws Exception            if the native conversion fails.
	 */
	public static void convertFromI420(VideoFrameBuffer src, byte[] dst, FourCC fourCC)
			throws Exception {
		if (src == null) {
			throw new NullPointerException("Source buffer must not be null");
		}
		if (dst == null) {
			throw new NullPointerException("Destination buffer must not be null");
		}
		
		I420Buffer i420 = src.toI420();
		
		I420toByteArray(
				i420.getDataY(), i420.getStrideY(),
				i420.getDataU(), i420.getStrideU(),
				i420.getDataV(), i420.getStrideV(),
				dst, 0, dst.length,
				i420.getWidth(), i420.getHeight(),
				fourCC.value());
	}

	/**
	 * Convert an arbitrary {@link VideoFrameBuffer} to the specified pixel format
	 * and write the result into a {@link ByteBuffer}. If the destination buffer
	 * is a direct buffer, a native direct path is used; otherwise its backing
	 * array is employed.
	 * <p>
	 * The result is written to the bytes between the buffer's position and its
	 * limit, starting at the position. The position and limit stay as they are,
	 * and no byte outside of them is touched.
	 *
	 * @param src    Source video frame buffer.
	 * @param dst    Writable destination {@link ByteBuffer}. Must not be read-only,
	 *               and must have enough bytes remaining for the converted frame.
	 * @param fourCC Target FourCC format identifier.
	 *
	 * @throws NullPointerException     if {@code src} or {@code dst} is {@code null}.
	 * @throws IllegalArgumentException if {@code dst} is read-only.
	 * @throws Exception                if the native conversion fails.
	 */
	public static void convertFromI420(VideoFrameBuffer src, ByteBuffer dst, FourCC fourCC)
			throws Exception {
		if (src == null) {
			throw new NullPointerException("Source buffer must not be null");
		}
		if (dst == null) {
			throw new NullPointerException("Destination buffer must not be null");
		}
		if (dst.isReadOnly()) {
			throw new IllegalArgumentException("Destination buffer must not be read-only");
		}
		
		I420Buffer i420 = src.toI420();
		
		if (dst.isDirect()) {
			// The slice starts at the position and ends at the limit, which
			// the native code sees as the buffer's address and capacity.
			I420toDirectBuffer(
					i420.getDataY(), i420.getStrideY(),
					i420.getDataU(), i420.getStrideU(),
					i420.getDataV(), i420.getStrideV(),
					dst.slice(),
					i420.getWidth(), i420.getHeight(),
					fourCC.value());
		}
		else {
			// A writable heap buffer always has an accessible array.
			I420toByteArray(
					i420.getDataY(), i420.getStrideY(),
					i420.getDataU(), i420.getStrideU(),
					i420.getDataV(), i420.getStrideV(),
					dst.array(), dst.arrayOffset() + dst.position(), dst.remaining(),
					i420.getWidth(), i420.getHeight(),
					fourCC.value());
		}
	}

	/**
	 * Convert a source frame stored in a byte array (encoded in the format indicated
	 * by {@code fourCC}) to I420 and write the planes into the provided {@link I420Buffer}.
	 *
	 * @param src    Source pixel data in the specified FourCC format.
	 * @param dst    Destination I420 buffer (pre-allocated).
	 * @param fourCC FourCC describing the layout of {@code src}.
	 *
	 * @throws NullPointerException if {@code src} or {@code dst} is {@code null}.
	 * @throws Exception            if the native conversion fails.
	 */
	public static void convertToI420(byte[] src, I420Buffer dst, FourCC fourCC)
			throws Exception {
		if (src == null) {
			throw new NullPointerException("Source buffer must not be null");
		}
		if (dst == null) {
			throw new NullPointerException("Destination buffer must not be null");
		}

		byteArrayToI420(
				src, 0, src.length,
				dst.getWidth(), dst.getHeight(),
				dst.getDataY(), dst.getStrideY(),
				dst.getDataU(), dst.getStrideU(),
				dst.getDataV(), dst.getStrideV(),
				fourCC.value());
	}

	/**
	 * Convert a source frame stored in a {@link ByteBuffer} (encoded in the format
	 * specified by {@code fourCC}) to I420 and write the result into the provided
	 * {@link I420Buffer}. Uses a direct native path for direct buffers; otherwise
	 * reads from the backing or a temporary array.
	 * <p>
	 * The source frame is read from the bytes between the buffer's position and
	 * its limit, starting at the position. The position and limit stay as they
	 * are.
	 *
	 * @param src    Source pixel data buffer.
	 * @param dst    Destination I420 buffer (pre-allocated).
	 * @param fourCC FourCC describing the layout of {@code src}.
	 *
	 * @throws NullPointerException if {@code src} or {@code dst} is {@code null}.
	 * @throws Exception            if the native conversion fails.
	 */
	public static void convertToI420(ByteBuffer src, I420Buffer dst, FourCC fourCC)
			throws Exception {
		if (src == null) {
			throw new NullPointerException("Source buffer must not be null");
		}
		if (dst == null) {
			throw new NullPointerException("Destination buffer must not be null");
		}

		if (src.isDirect()) {
			// The slice starts at the position and ends at the limit, which
			// the native code sees as the buffer's address and capacity.
			directBufferToI420(
					src.slice(),
					dst.getWidth(), dst.getHeight(),
					dst.getDataY(), dst.getStrideY(),
					dst.getDataU(), dst.getStrideU(),
					dst.getDataV(), dst.getStrideV(),
					fourCC.value());
		}
		else {
			byte[] array;
			int offset;
			int length = src.remaining();

			if (src.hasArray()) {
				array = src.array();
				offset = src.arrayOffset() + src.position();
			}
			else {
				// Read-only, so the array is not accessible. Copied through a
				// duplicate, which leaves the position of the buffer as it is.
				array = new byte[length];
				offset = 0;
				src.duplicate().get(array);
			}

			byteArrayToI420(
					array, offset, length,
					dst.getWidth(), dst.getHeight(),
					dst.getDataY(), dst.getStrideY(),
					dst.getDataU(), dst.getStrideU(),
					dst.getDataV(), dst.getStrideV(),
					fourCC.value());
		}
	}

	private native static void I420toByteArray(
			ByteBuffer srcY, int srcStrideY,
			ByteBuffer srcU, int srcStrideU,
			ByteBuffer srcV, int srcStrideV,
			byte[] dst, int dstOffset, int dstLength,
			int width, int height,
			int fourCC) throws Exception;
	
	private native static void I420toDirectBuffer(
			ByteBuffer srcY, int srcStrideY,
			ByteBuffer srcU, int srcStrideU,
			ByteBuffer srcV, int srcStrideV,
			ByteBuffer dst,
			int width, int height,
			int fourCC) throws Exception;

	private native static void byteArrayToI420(
			byte[] src, int srcOffset, int srcLength,
			int width, int height,
			ByteBuffer dstY, int dstStrideY,
			ByteBuffer dstU, int dstStrideU,
			ByteBuffer dstV, int dstStrideV,
			int fourCC) throws Exception;

	private native static void directBufferToI420(
			ByteBuffer src,
			int width, int height,
			ByteBuffer dstY, int dstStrideY,
			ByteBuffer dstU, int dstStrideU,
			ByteBuffer dstV, int dstStrideV,
			int fourCC) throws Exception;

}
