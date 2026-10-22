/*
 * Copyright 2025 Alex Andres
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.FourCC;

import org.junit.jupiter.api.Test;

class VideoBufferConverterTest extends TestBase {

	private static final int WIDTH = 32;
	private static final int HEIGHT = 8;
	private static final int RGBA_SIZE = 4 * WIDTH * HEIGHT;

	@Test
	void convertFromI420ToByteArray() throws Exception {
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		byte[] rgba = new byte[4 * WIDTH * HEIGHT];
		VideoBufferConverter.convertFromI420(i420, rgba, FourCC.RGBA);
		verifyI420Buffer(i420, false);
		verifyRGBAArray(rgba, true);
	}

	@Test
	void convertFromI420ToDirectBuffer() throws Exception {
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		ByteBuffer rgba = ByteBuffer.allocateDirect(4 * WIDTH * HEIGHT);
		VideoBufferConverter.convertFromI420(i420, rgba, FourCC.RGBA);
		verifyI420Buffer(i420, false);
		verifyRGBABuffer(rgba, true);
	}

	@Test
	void convertFromI420ToNonDirectBuffer() throws Exception {
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		ByteBuffer rgba = ByteBuffer.allocate(4 * WIDTH * HEIGHT);
		VideoBufferConverter.convertFromI420(i420, rgba, FourCC.RGBA);
		verifyI420Buffer(i420, false);
		verifyRGBABuffer(rgba, true);
	}

	@Test
	void convertFromI420ToReadOnlyDirectBuffer() throws Exception {
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		ByteBuffer rgba = ByteBuffer.allocateDirect(4 * WIDTH * HEIGHT);
		assertThrows(IllegalArgumentException.class, () -> {
			VideoBufferConverter.convertFromI420(i420, rgba.asReadOnlyBuffer(), FourCC.RGBA);
		});
	}

	@Test
	void convertFromI420ToReadOnlyNonDirectBuffer() throws Exception {
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		ByteBuffer rgba = ByteBuffer.allocate(4 * WIDTH * HEIGHT);
		assertThrows(IllegalArgumentException.class, () -> {
			VideoBufferConverter.convertFromI420(i420, rgba.asReadOnlyBuffer(), FourCC.RGBA);
		});
	}

	@Test
	void convertFromI420ToSmallByteArray() throws Exception {
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		byte[] rgba = new byte[4 * WIDTH * HEIGHT - 1];
		assertThrows(RuntimeException.class, () -> {
			VideoBufferConverter.convertFromI420(i420, rgba, FourCC.RGBA);
		});
	}

	@Test
	void convertFromI420ToSmallDirectBuffer() throws Exception {
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		ByteBuffer rgba = ByteBuffer.allocateDirect(4 * WIDTH * HEIGHT - 1);
		assertThrows(RuntimeException.class, () -> {
			VideoBufferConverter.convertFromI420(i420, rgba, FourCC.RGBA);
		});
	}

	@Test
	void convertFromI420ToSmallNonDirectBuffer() throws Exception {
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		ByteBuffer rgba = ByteBuffer.allocate(4 * WIDTH * HEIGHT - 1);
		assertThrows(RuntimeException.class, () -> {
			VideoBufferConverter.convertFromI420(i420, rgba, FourCC.RGBA);
		});
	}

	@Test
	void convertFromByteArrayToI420() throws Exception {
		byte[] rgba = new byte[4 * WIDTH * HEIGHT];
		initializeRGBAArray(rgba);
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		VideoBufferConverter.convertToI420(rgba, i420, FourCC.RGBA);
		verifyRGBAArray(rgba, false);
		verifyI420Buffer(i420, true);
	}

	@Test
	void convertFromDirectBufferToI420() throws Exception {
		ByteBuffer rgba = ByteBuffer.allocateDirect(4 * WIDTH * HEIGHT);
		initializeRGBABuffer(rgba);
		rgba.rewind();
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		VideoBufferConverter.convertToI420(rgba, i420, FourCC.RGBA);
		verifyRGBABuffer(rgba, false);
		verifyI420Buffer(i420, true);
	}

	@Test
	void convertFromNonDirectBufferToI420() throws Exception {
		ByteBuffer rgba = ByteBuffer.allocate(4 * WIDTH * HEIGHT);
		initializeRGBABuffer(rgba);
		rgba.rewind();
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		VideoBufferConverter.convertToI420(rgba, i420, FourCC.RGBA);
		verifyRGBABuffer(rgba, false);
		verifyI420Buffer(i420, true);
	}

	@Test
	void convertFromReadOnlyDirectBufferToI420() throws Exception {
		ByteBuffer rgba = ByteBuffer.allocateDirect(4 * WIDTH * HEIGHT);
		initializeRGBABuffer(rgba);
		rgba.rewind();
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		VideoBufferConverter.convertToI420(rgba.asReadOnlyBuffer(), i420, FourCC.RGBA);
		verifyRGBABuffer(rgba, false);
		verifyI420Buffer(i420, true);
	}

	@Test
	void convertFromReadOnlyNonDirectBufferToI420() throws Exception {
		ByteBuffer rgba = ByteBuffer.allocate(4 * WIDTH * HEIGHT);
		initializeRGBABuffer(rgba);
		rgba.rewind();
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		VideoBufferConverter.convertToI420(rgba.asReadOnlyBuffer(), i420, FourCC.RGBA);
		verifyRGBABuffer(rgba, false);
		verifyI420Buffer(i420, true);
	}

	@Test
	void convertFromSmallByteArrayToI420() throws Exception {
		byte[] rgba = new byte[4 * WIDTH * HEIGHT - 1];
		initializeRGBAArray(rgba);
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		assertThrows(RuntimeException.class, () -> {
			VideoBufferConverter.convertToI420(rgba, i420, FourCC.RGBA);
		});
	}

	@Test
	void convertFromSmallDirectBufferToI420() throws Exception {
		ByteBuffer rgba = ByteBuffer.allocateDirect(4 * WIDTH * HEIGHT - 1);
		initializeRGBABuffer(rgba);
		rgba.rewind();
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		assertThrows(RuntimeException.class, () -> {
			VideoBufferConverter.convertToI420(rgba, i420, FourCC.RGBA);
		});
	}

	@Test
	void convertFromSmallNonDirectBufferToI420() throws Exception {
		ByteBuffer rgba = ByteBuffer.allocate(4 * WIDTH * HEIGHT - 1);
		initializeRGBABuffer(rgba);
		rgba.rewind();
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		assertThrows(RuntimeException.class, () -> {
			VideoBufferConverter.convertToI420(rgba, i420, FourCC.RGBA);
		});
	}

	@Test
	void convertFromI420ToDirectWindow() throws Exception {
		convertFromI420ToWindow(new Window(true, RGBA_SIZE));
	}

	@Test
	void convertFromI420ToNonDirectWindow() throws Exception {
		convertFromI420ToWindow(new Window(false, RGBA_SIZE));
	}

	@Test
	void convertFromI420ToSmallDirectWindow() throws Exception {
		convertFromI420ToSmallWindow(new Window(true, RGBA_SIZE - 1));
	}

	@Test
	void convertFromI420ToSmallNonDirectWindow() throws Exception {
		convertFromI420ToSmallWindow(new Window(false, RGBA_SIZE - 1));
	}

	@Test
	void convertFromDirectWindowToI420() throws Exception {
		Window window = new Window(true, RGBA_SIZE);
		convertFromWindowToI420(window, window.buffer);
	}

	@Test
	void convertFromNonDirectWindowToI420() throws Exception {
		Window window = new Window(false, RGBA_SIZE);
		convertFromWindowToI420(window, window.buffer);
	}

	@Test
	void convertFromReadOnlyDirectWindowToI420() throws Exception {
		Window window = new Window(true, RGBA_SIZE);
		convertFromWindowToI420(window, window.buffer.asReadOnlyBuffer());
	}

	@Test
	void convertFromReadOnlyNonDirectWindowToI420() throws Exception {
		Window window = new Window(false, RGBA_SIZE);
		convertFromWindowToI420(window, window.buffer.asReadOnlyBuffer());
	}

	@Test
	void convertFromSmallDirectWindowToI420() throws Exception {
		convertFromSmallWindowToI420(new Window(true, RGBA_SIZE - 1));
	}

	@Test
	void convertFromSmallNonDirectWindowToI420() throws Exception {
		convertFromSmallWindowToI420(new Window(false, RGBA_SIZE - 1));
	}

	private void convertFromI420ToWindow(Window window) throws Exception {
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		byte[] expected = new byte[RGBA_SIZE];
		VideoBufferConverter.convertFromI420(i420, expected, FourCC.RGBA);

		VideoBufferConverter.convertFromI420(i420, window.buffer, FourCC.RGBA);

		window.verifyUnmoved();
		window.verifyGuards();
		assertArrayEquals(expected, window.contents());
	}

	private void convertFromI420ToSmallWindow(Window window) throws Exception {
		// The buffer's capacity would hold the frame, the window does not.
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		initializeI420Buffer(i420);
		assertTrue(window.buffer.capacity() >= RGBA_SIZE);

		assertThrows(RuntimeException.class, () -> {
			VideoBufferConverter.convertFromI420(i420, window.buffer, FourCC.RGBA);
		});

		window.verifyUnmoved();
		window.verifyGuards();
		window.verifyUntouched();
	}

	private void convertFromWindowToI420(Window window, ByteBuffer src) throws Exception {
		byte[] rgba = new byte[RGBA_SIZE];
		initializeRGBAArray(rgba);
		NativeI420Buffer expected = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		VideoBufferConverter.convertToI420(rgba, expected, FourCC.RGBA);

		window.fill(rgba);
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);
		VideoBufferConverter.convertToI420(src, i420, FourCC.RGBA);

		assertEquals(Window.GUARD, src.position());
		assertEquals(Window.GUARD + RGBA_SIZE, src.limit());
		verifyI420Equals(expected, i420);
	}

	private void convertFromSmallWindowToI420(Window window) throws Exception {
		// The bytes after the window would make up a frame, the window does not.
		byte[] rgba = new byte[RGBA_SIZE - 1];
		initializeRGBAArray(rgba);
		window.fill(rgba);
		NativeI420Buffer i420 = NativeI420Buffer.allocate(WIDTH, HEIGHT);

		assertThrows(RuntimeException.class, () -> {
			VideoBufferConverter.convertToI420(window.buffer, i420, FourCC.RGBA);
		});

		window.verifyUnmoved();
	}

	private void initializeI420Buffer(I420Buffer i420) {
		ByteBuffer dataY = i420.getDataY();
		int strideY = i420.getStrideY();
		ByteBuffer dataU = i420.getDataU();
		int strideU = i420.getStrideU();
		ByteBuffer dataV = i420.getDataV();
		int strideV = i420.getStrideV();

		byte value = 0;
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				dataY.put(y * strideY + x, value++);
			}
		}
		for (int y = 0; y < HEIGHT / 2; y++) {
			for (int x = 0; x < WIDTH / 2; x++) {
				dataU.put(y * strideU + x, value++);
				dataV.put(y * strideV + x, value++);
			}
		}
	}

	private void initializeRGBAArray(byte[] rgba) {
		byte value = 0;
		for (int i = 0; i < rgba.length; i++) {
			rgba[i] = value++;
		}
	}

	private void initializeRGBABuffer(ByteBuffer rgba) {
		byte value = 0;
		for (int i = 0; i < rgba.limit(); i++) {
			rgba.put(value++);
		}
	}

	private void verifyI420Buffer(I420Buffer i420, boolean expectingChanges) {
		assertEquals(WIDTH, i420.getWidth());
		assertEquals(HEIGHT, i420.getHeight());

		ByteBuffer dataY = i420.getDataY();
		int strideY = i420.getStrideY();
		ByteBuffer dataU = i420.getDataU();
		int strideU = i420.getStrideU();
		ByteBuffer dataV = i420.getDataV();
		int strideV = i420.getStrideV();

		if (expectingChanges) {
			boolean nonZeroY = false;
			boolean nonZeroU = false;
			boolean nonZeroV = false;

			for (int y = 0; y < HEIGHT; y++) {
				for (int x = 0; x < WIDTH; x++) {
					nonZeroY |= (dataY.get(y * strideY + x) != 0);
				}
			}
			for (int y = 0; y < HEIGHT / 2; y++) {
				for (int x = 0; x < WIDTH / 2; x++) {
					nonZeroU |= (dataU.get(y * strideU + x) != 0);
					nonZeroV |= (dataV.get(y * strideV + x) != 0);
				}
			}

			assertTrue(nonZeroY);
			assertTrue(nonZeroU);
			assertTrue(nonZeroV);
		}
		else {
			byte value = 0;
			for (int y = 0; y < HEIGHT; y++) {
				for (int x = 0; x < WIDTH; x++) {
					assertEquals(value++, dataY.get(y * strideY + x));
				}
			}
			for (int y = 0; y < HEIGHT / 2; y++) {
				for (int x = 0; x < WIDTH / 2; x++) {
					assertEquals(value++, dataU.get(y * strideU + x));
					assertEquals(value++, dataV.get(y * strideV + x));
				}
			}
		}
	}

	private void verifyRGBAArray(byte[] rgba, boolean expectingChanges) {
		assertEquals(4 * WIDTH * HEIGHT, rgba.length);

		if (expectingChanges) {
			boolean nonZeroR = false;
			boolean nonZeroG = false;
			boolean nonZeroB = false;

			for (int i = 0; i < 4 * WIDTH * HEIGHT; i += 4) {
				byte a = rgba[i + 0];
				byte b = rgba[i + 1];
				byte g = rgba[i + 2];
				byte r = rgba[i + 3];
				assertEquals((byte) -1, a);
				nonZeroB |= (b != 0);
				nonZeroG |= (g != 0);
				nonZeroR |= (r != 0);
			}

			assertTrue(nonZeroR);
			assertTrue(nonZeroG);
			assertTrue(nonZeroB);
		}
		else {
			byte value = 0;
			for (int i = 0; i < 4 * WIDTH * HEIGHT; i++) {
				assertEquals(value++, rgba[i]);
			}
		}
	}

	private void verifyRGBABuffer(ByteBuffer rgba, boolean expectingChanges) {
		assertEquals(4 * WIDTH * HEIGHT, rgba.limit());

		if (expectingChanges) {
			boolean nonZeroR = false;
			boolean nonZeroG = false;
			boolean nonZeroB = false;

			for (int i = 0; i < 4 * WIDTH * HEIGHT; i += 4) {
				byte a = rgba.get();
				byte b = rgba.get();
				byte g = rgba.get();
				byte r = rgba.get();
				assertEquals((byte) -1, a);
				nonZeroB |= (b != 0);
				nonZeroG |= (g != 0);
				nonZeroR |= (r != 0);
			}

			assertTrue(nonZeroR);
			assertTrue(nonZeroG);
			assertTrue(nonZeroB);
		}
		else {
			byte value = 0;
			for (int i = 0; i < 4 * WIDTH * HEIGHT; i++) {
				assertEquals(value++, rgba.get());
			}
		}
	}

	private void verifyI420Equals(I420Buffer expected, I420Buffer actual) {
		for (int y = 0; y < HEIGHT; y++) {
			for (int x = 0; x < WIDTH; x++) {
				assertEquals(expected.getDataY().get(y * expected.getStrideY() + x),
						actual.getDataY().get(y * actual.getStrideY() + x));
			}
		}
		for (int y = 0; y < HEIGHT / 2; y++) {
			for (int x = 0; x < WIDTH / 2; x++) {
				assertEquals(expected.getDataU().get(y * expected.getStrideU() + x),
						actual.getDataU().get(y * actual.getStrideU() + x));
				assertEquals(expected.getDataV().get(y * expected.getStrideV() + x),
						actual.getDataV().get(y * actual.getStrideV() + x));
			}
		}
	}



	/**
	 * A window between position and limit of a buffer that is a slice of a
	 * larger one, so that the buffer has an offset of its own as well as a
	 * position. Guard bytes surround the window on both sides.
	 */
	private static class Window {

		static final int GUARD = 16;

		static final byte GUARD_VALUE = (byte) 0x5A;

		/** The whole memory, of which the window is a part. */
		final ByteBuffer whole;

		final ByteBuffer buffer;

		final int length;


		Window(boolean direct, int length) {
			this.length = length;

			int size = GUARD + GUARD + length + GUARD;
			whole = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);

			for (int i = 0; i < size; i++) {
				whole.put(i, GUARD_VALUE);
			}

			whole.position(GUARD);
			buffer = whole.slice();
			whole.position(0);

			buffer.position(GUARD);
			buffer.limit(GUARD + length);
		}

		void fill(byte[] data) {
			for (int i = 0; i < data.length; i++) {
				buffer.put(GUARD + i, data[i]);
			}
		}

		byte[] contents() {
			byte[] contents = new byte[length];
			buffer.duplicate().get(contents);
			return contents;
		}

		void verifyUnmoved() {
			assertEquals(GUARD, buffer.position());
			assertEquals(GUARD + length, buffer.limit());
		}

		void verifyGuards() {
			int start = GUARD + GUARD;

			for (int i = 0; i < whole.capacity(); i++) {
				if (i < start || i >= start + length) {
					assertEquals(GUARD_VALUE, whole.get(i), "Guard byte " + i + " was overwritten");
				}
			}
		}

		void verifyUntouched() {
			for (byte value : contents()) {
				assertEquals(GUARD_VALUE, value);
			}
		}
	}

}