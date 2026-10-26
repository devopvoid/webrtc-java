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

package dev.onvoid.webrtc.media.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Buffers of the application's own, which provide their pixels through {@link VideoFrameBuffer#toI420()}.
 */
class CustomFrameBufferTest extends TestBase {

	private static final int WIDTH = 6;

	private static final int HEIGHT = 4;

	private CustomVideoSource videoSource;

	private VideoTrack videoTrack;

	/** The luma of every frame that reached the track, row by row. */
	private final List<byte[]> received = new ArrayList<>();

	private final VideoTrackSink sink = frame -> received.add(lumaOf(frame.buffer.toI420()));


	@BeforeEach
	void init() {
		// The instance, and so the list, lives as long as the class.
		received.clear();

		videoSource = new CustomVideoSource();
		videoTrack = factory.createVideoTrack("videoTrack", videoSource);
		videoTrack.addSink(sink);
	}

	@AfterEach
	void dispose() {
		videoTrack.removeSink(sink);
		videoTrack.dispose();
		videoSource.dispose();
	}

	@Test
	void copyWrappedNativeBuffer() {
		NativeI420Buffer pixels = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		fillLuma(pixels);

		VideoFrame frame = new VideoFrame(new WrappingBuffer(pixels), 0);
		VideoFrame copy = frame.copy();

		assertNotSame(pixels, copy.buffer);
		assertArrayEquals(expectedLuma(), lumaOf(copy.buffer.toI420()));

		// The copy has pixels of its own.
		frame.release();

		assertArrayEquals(expectedLuma(), lumaOf(copy.buffer.toI420()));

		copy.release();
	}

	@Test
	void copyCustomI420Buffer() {
		VideoFrame frame = new VideoFrame(new DirectI420Buffer(WIDTH + 3, 0), 0);
		VideoFrame copy = frame.copy();

		assertArrayEquals(expectedLuma(), lumaOf(copy.buffer.toI420()));

		copy.release();
	}

	@Test
	void copyReleasedBuffer() {
		NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		buffer.release();

		assertThrows(RuntimeException.class, buffer::copy);
		assertThrows(RuntimeException.class, () -> new VideoFrame(buffer, 0).copy());
	}

	@Test
	void pushWrappedNativeBuffer() {
		NativeI420Buffer pixels = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		fillLuma(pixels);

		VideoFrame frame = new VideoFrame(new WrappingBuffer(pixels), 0);
		videoSource.pushFrame(frame);
		frame.release();

		assertReceived();
	}

	@Test
	void pushCustomI420Buffer() {
		// Padded rows, as many capture APIs deliver them.
		VideoFrame frame = new VideoFrame(new DirectI420Buffer(WIDTH + 3, 0), 0);
		videoSource.pushFrame(frame);

		assertReceived();
	}

	@Test
	void pushBufferWithShortPlanes() {
		// Each plane lacks the last byte that its strides and height require.
		VideoFrame frame = new VideoFrame(new DirectI420Buffer(WIDTH, -1), 0);

		assertThrows(IllegalArgumentException.class, () -> videoSource.pushFrame(frame));
		assertThrows(IllegalArgumentException.class, frame::copy);
		assertEquals(0, received.size());
	}

	@Test
	void pushBufferWithShortStride() {
		VideoFrame frame = new VideoFrame(new DirectI420Buffer(WIDTH - 1, 0), 0);

		assertThrows(IllegalArgumentException.class, () -> videoSource.pushFrame(frame));
		assertEquals(0, received.size());
	}

	@Test
	void pushHeapBuffer() {
		DirectI420Buffer buffer = new DirectI420Buffer(WIDTH, 0) {

			@Override
			public ByteBuffer getDataY() {
				return ByteBuffer.allocate(WIDTH * HEIGHT);
			}
		};
		VideoFrame frame = new VideoFrame(buffer, 0);

		assertThrows(RuntimeException.class, () -> videoSource.pushFrame(frame));
		assertEquals(0, received.size());
	}

	private void assertReceived() {
		assertEquals(1, received.size());
		assertArrayEquals(expectedLuma(), received.get(0));
	}

	private static void fillLuma(I420Buffer buffer) {
		ByteBuffer y = buffer.getDataY();

		for (int row = 0; row < buffer.getHeight(); row++) {
			for (int col = 0; col < buffer.getWidth(); col++) {
				y.put(row * buffer.getStrideY() + col, lumaValue(row, col));
			}
		}
	}

	private static byte[] lumaOf(I420Buffer buffer) {
		ByteBuffer y = buffer.getDataY();
		byte[] luma = new byte[buffer.getWidth() * buffer.getHeight()];

		for (int row = 0; row < buffer.getHeight(); row++) {
			for (int col = 0; col < buffer.getWidth(); col++) {
				luma[row * buffer.getWidth() + col] = y.get(row * buffer.getStrideY() + col);
			}
		}

		return luma;
	}

	private static byte[] expectedLuma() {
		byte[] luma = new byte[WIDTH * HEIGHT];

		for (int row = 0; row < HEIGHT; row++) {
			for (int col = 0; col < WIDTH; col++) {
				luma[row * WIDTH + col] = lumaValue(row, col);
			}
		}

		return luma;
	}

	private static byte lumaValue(int row, int col) {
		return (byte) (16 + row * 16 + col);
	}



	/**
	 * A buffer that is not an I420Buffer itself and keeps its pixels in a NativeI420Buffer.
	 */
	private static class WrappingBuffer implements VideoFrameBuffer {

		private final NativeI420Buffer pixels;


		WrappingBuffer(NativeI420Buffer pixels) {
			this.pixels = pixels;
		}

		@Override
		public int getWidth() {
			return pixels.getWidth();
		}

		@Override
		public int getHeight() {
			return pixels.getHeight();
		}

		@Override
		public I420Buffer toI420() {
			return pixels;
		}

		@Override
		public VideoFrameBuffer cropAndScale(int cropX, int cropY, int cropWidth,
				int cropHeight, int scaleWidth, int scaleHeight) {
			return pixels.cropAndScale(cropX, cropY, cropWidth, cropHeight, scaleWidth, scaleHeight);
		}

		@Override
		public void retain() {
			pixels.retain();
		}

		@Override
		public void release() {
			pixels.release();
		}
	}



	/**
	 * An I420 buffer in direct byte buffers of its own, with a luma stride of choice.
	 */
	private static class DirectI420Buffer implements I420Buffer {

		private final int strideY;

		private final int strideUV;

		private final ByteBuffer dataY;

		private final ByteBuffer dataU;

		private final ByteBuffer dataV;


		/**
		 * @param strideY    The stride of the luma plane, which may be shorter than the width.
		 * @param extraBytes Added to the size that each plane needs, so a negative number makes it too short.
		 */
		DirectI420Buffer(int strideY, int extraBytes) {
			this.strideY = strideY;
			this.strideUV = (WIDTH + 1) / 2;

			int chromaHeight = (HEIGHT + 1) / 2;

			dataY = ByteBuffer.allocateDirect(Math.max(strideY, WIDTH) * (HEIGHT - 1) + WIDTH + extraBytes);
			dataU = ByteBuffer.allocateDirect(strideUV * chromaHeight + extraBytes);
			dataV = ByteBuffer.allocateDirect(strideUV * chromaHeight + extraBytes);

			if (strideY >= WIDTH && extraBytes >= 0) {
				fillLuma(this);
			}
		}

		@Override
		public ByteBuffer getDataY() {
			return dataY.slice();
		}

		@Override
		public ByteBuffer getDataU() {
			return dataU.slice();
		}

		@Override
		public ByteBuffer getDataV() {
			return dataV.slice();
		}

		@Override
		public int getStrideY() {
			return strideY;
		}

		@Override
		public int getStrideU() {
			return strideUV;
		}

		@Override
		public int getStrideV() {
			return strideUV;
		}

		@Override
		public int getWidth() {
			return WIDTH;
		}

		@Override
		public int getHeight() {
			return HEIGHT;
		}

		@Override
		public I420Buffer toI420() {
			return this;
		}

		@Override
		public VideoFrameBuffer cropAndScale(int cropX, int cropY, int cropWidth,
				int cropHeight, int scaleWidth, int scaleHeight) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void retain() {
		}

		@Override
		public void release() {
		}
	}
}
