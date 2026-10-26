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

import dev.onvoid.webrtc.internal.RefCountedObject;

import java.nio.ByteBuffer;

/**
 * This class wraps the native WebRTC I420BufferInterface.
 */
public class NativeI420Buffer extends RefCountedObject implements I420Buffer {

	private final ByteBuffer dataY;
	private final ByteBuffer dataU;
	private final ByteBuffer dataV;
	
	private final int strideY;
	private final int strideU;
	private final int strideV;
	
	private final int width;
	private final int height;


	private NativeI420Buffer(int width, int height, ByteBuffer dataY, int strideY, ByteBuffer dataU, int strideU,
			ByteBuffer dataV, int strideV) {
		this.width = width;
		this.height = height;
		this.dataY = dataY;
		this.strideY = strideY;
		this.dataU = dataU;
		this.strideU = strideU;
		this.dataV = dataV;
		this.strideV = strideV;
	}

	@Override
	public ByteBuffer getDataY() {
		// Return a slice to prevent relative reads from changing the position.
		return dataY.slice();
	}

	@Override
	public ByteBuffer getDataU() {
		// Return a slice to prevent relative reads from changing the position.
		return dataU.slice();
	}

	@Override
	public ByteBuffer getDataV() {
		// Return a slice to prevent relative reads from changing the position.
		return dataV.slice();
	}

	@Override
	public int getStrideY() {
		return strideY;
	}

	@Override
	public int getStrideU() {
		return strideU;
	}

	@Override
	public int getStrideV() {
		return strideV;
	}
	
	@Override
	public int getWidth() {
		return width;
	}

	@Override
	public int getHeight() {
		return height;
	}

	@Override
	public I420Buffer toI420() {
	    return this;
	}

	@Override
	public VideoFrameBuffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight,
			int scaleWidth, int scaleHeight) {
		return cropAndScale(this, cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight);
	}

	@Override
	public String toString() {
		return String.format("%s@%d [width=%s, height=%s]",
						NativeI420Buffer.class.getSimpleName(), hashCode(),
						width, height);
	}

	/**
	 * Creates a copy of this I420 buffer. This method performs a deep copy of all data in the buffer.
	 *
	 * @return A new NativeI420Buffer instance with the same dimensions and data as this buffer.
	 *
	 * @throws RuntimeException if this buffer has been released.
	 */
	public NativeI420Buffer copy() {
		return copy(this);
	}

	/**
	 * Allocates an empty I420Buffer suitable for an image of the given dimensions.
	 *
	 * @param width  The width of the image in pixels.
	 * @param height The height of the image in pixels.
	 *
	 * @return A new buffer of the given dimensions.
	 *
	 * @throws IllegalArgumentException if a dimension is not positive or all three planes together
	 *                                  are larger than {@link Integer#MAX_VALUE} bytes.
	 */
	public static NativeI420Buffer allocate(int width, int height) {
		// WebRTC aborts the process on dimensions it cannot allocate, rather than failing the call.
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Invalid buffer size " + width + "x" + height);
		}

		long chromaSize = ((width + 1L) / 2) * ((height + 1L) / 2);

		if ((long) width * height + 2 * chromaSize > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Buffer size " + width + "x" + height + " is too large");
		}

		return allocateBuffer(width, height);
	}

	private static native NativeI420Buffer allocateBuffer(int width, int height);

	/**
	 * Copies the pixels of any buffer, as {@link VideoFrameBuffer#toI420()} provides them, into a new
	 * buffer.
	 *
	 * @throws RuntimeException if the buffer has no readable pixels, such as a released buffer or one
	 *                          with heap byte buffers, or if the size, strides and planes of the I420
	 *                          buffer do not fit together.
	 */
	static native NativeI420Buffer copy(VideoFrameBuffer buffer);

	/**
	 * Crops this buffer without copying. The cropped buffer shares the pixels of this one and holds a
	 * reference to it, so it stays valid when this buffer is released.
	 */
	private native NativeI420Buffer crop(int cropX, int cropY, int cropWidth, int cropHeight);

	private static VideoFrameBuffer cropAndScale(final NativeI420Buffer buffer, int cropX, int cropY,
			int cropWidth, int cropHeight, int scaleWidth, int scaleHeight) {
		if (cropX < 0 || cropY < 0 || cropWidth <= 0 || cropHeight <= 0
				|| cropWidth > buffer.getWidth() - cropX || cropHeight > buffer.getHeight() - cropY) {
			throw new IllegalArgumentException("Crop rectangle " + cropWidth + "x" + cropHeight + " at ("
					+ cropX + ", " + cropY + ") does not fit the " + buffer.getWidth() + "x"
					+ buffer.getHeight() + " buffer");
		}
		if (scaleWidth <= 0 || scaleHeight <= 0) {
			throw new IllegalArgumentException("Invalid scale size " + scaleWidth + "x" + scaleHeight);
		}

		if (cropWidth == scaleWidth && cropHeight == scaleHeight) {
			// No scaling.
			return buffer.crop(cropX, cropY, cropWidth, cropHeight);
		}

		I420Buffer newBuffer = allocate(scaleWidth, scaleHeight);

		cropAndScale(buffer.getDataY(), buffer.getStrideY(), buffer.getDataU(), buffer.getStrideU(),
				buffer.getDataV(), buffer.getStrideV(), cropX, cropY, cropWidth, cropHeight,
				newBuffer.getDataY(), newBuffer.getStrideY(), newBuffer.getDataU(), newBuffer.getStrideU(),
				newBuffer.getDataV(), newBuffer.getStrideV(), scaleWidth, scaleHeight);

		return newBuffer;
	}

	private static native void cropAndScale(ByteBuffer srcY, int srcStrideY,
		      ByteBuffer srcU, int srcStrideU, ByteBuffer srcV, int srcStrideV, int cropX, int cropY,
		      int cropWidth, int cropHeight, ByteBuffer dstY, int dstStrideY, ByteBuffer dstU,
		      int dstStrideU, ByteBuffer dstV, int dstStrideV, int scaleWidth, int scaleHeight);
}
