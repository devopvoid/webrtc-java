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
		retain();
		
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
	 * Allocates an empty I420Buffer suitable for an image of the given dimensions.
	 */
	public static native NativeI420Buffer allocate(int width, int height);

	/**
	 * Wraps existing ByteBuffers into NativeI420Buffer object without copying the
	 * contents.
	 */
	private static I420Buffer wrap(int width, int height, ByteBuffer dataY, int strideY, ByteBuffer dataU,
			int strideU, ByteBuffer dataV, int strideV) {
		if (dataY == null || dataU == null || dataV == null) {
			throw new IllegalArgumentException("Data buffers cannot be null");
		}
		if (!dataY.isDirect() || !dataU.isDirect() || !dataV.isDirect()) {
			throw new IllegalArgumentException("Data buffers must be direct byte buffers");
		}

		// Slice the buffers to prevent external modifications to the position / limit
		// of the buffer.
		// Note that this doesn't protect the contents of the buffers from modifications.
		dataY = dataY.slice();
		dataU = dataU.slice();
		dataV = dataV.slice();

		final int chromaWidth = (width + 1) / 2;
		final int chromaHeight = (height + 1) / 2;
		
		checkCapacity(dataY, width, height, strideY);
		checkCapacity(dataU, chromaWidth, chromaHeight, strideU);
		checkCapacity(dataV, chromaWidth, chromaHeight, strideV);

		return new NativeI420Buffer(width, height, dataY, strideY, dataU, strideU, dataV, strideV);
	}
	
	private static VideoFrameBuffer cropAndScale(final I420Buffer buffer, int cropX, int cropY,
			int cropWidth, int cropHeight, int scaleWidth, int scaleHeight) {
		if (cropWidth == scaleWidth && cropHeight == scaleHeight) {
			// No scaling.
			ByteBuffer dataY = buffer.getDataY();
			ByteBuffer dataU = buffer.getDataU();
			ByteBuffer dataV = buffer.getDataV();

			dataY.position(cropX + cropY * buffer.getStrideY());
			dataU.position(cropX / 2 + cropY / 2 * buffer.getStrideU());
			dataV.position(cropX / 2 + cropY / 2 * buffer.getStrideV());

			buffer.retain();

			return wrap(scaleWidth, scaleHeight, dataY.slice(), buffer.getStrideY(), dataU.slice(),
					buffer.getStrideU(), dataV.slice(), buffer.getStrideV());
		}

		I420Buffer newBuffer = allocate(scaleWidth, scaleHeight);
		
		cropAndScale(buffer.getDataY(), buffer.getStrideY(), buffer.getDataU(), buffer.getStrideU(),
				buffer.getDataV(), buffer.getStrideV(), cropX, cropY, cropWidth, cropHeight,
				newBuffer.getDataY(), newBuffer.getStrideY(), newBuffer.getDataU(), newBuffer.getStrideU(),
				newBuffer.getDataV(), newBuffer.getStrideV(), scaleWidth, scaleHeight);
		
		return newBuffer;
	}

	private static void checkCapacity(ByteBuffer data, int width, int height, int stride) {
		// The last row does not necessarily need padding.
		final int minCapacity = stride * (height - 1) + width;
		
		if (data.capacity() < minCapacity) {
			throw new IllegalArgumentException("Buffer must be at least " + minCapacity +
					" bytes, but was " + data.capacity());
		}
	}

	private static native void cropAndScale(ByteBuffer srcY, int srcStrideY,
		      ByteBuffer srcU, int srcStrideU, ByteBuffer srcV, int srcStrideV, int cropX, int cropY,
		      int cropWidth, int cropHeight, ByteBuffer dstY, int dstStrideY, ByteBuffer dstU,
		      int dstStrideU, ByteBuffer dstV, int dstStrideV, int scaleWidth, int scaleHeight);
}
