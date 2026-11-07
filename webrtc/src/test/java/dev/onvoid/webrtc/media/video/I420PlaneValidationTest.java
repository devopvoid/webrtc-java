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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.FourCC;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class I420PlaneValidationTest extends TestBase {

	@TestFactory
	List<DynamicTest> rejectsInvalidPlanes() {
		List<DynamicTest> tests = new ArrayList<>();
		for (int plane = 0; plane < 3; plane++) {
			for (String invalid : new String[] { "short", "heap", "null", "stride", "zero", "negative", "overflow" }) {
				for (int storage = 0; storage < 3; storage++) {
					for (boolean destination : new boolean[] { false, true }) {
						final int selected = plane;
						final int path = storage;
						String name = "YUV".charAt(plane) + " " + invalid + " path=" + path + " destination=" + destination;
						tests.add(DynamicTest.dynamicTest(name, () -> {
							Planes frame = new Planes(3, 3);
							frame.invalidate(selected, invalid);
							Packed packed = new Packed(path, 3 * 3 * 4);
							RuntimeException failure = assertThrows(RuntimeException.class,
									() -> convert(frame, packed, destination));
							assertTrue(failure.getMessage().contains("" + "YUV".charAt(selected)), failure::getMessage);
							frame.assertUntouched();
							packed.assertUntouched();
						}));
					}
				}
			}
		}
		return tests;
	}

	@Test
	void rejectsReadOnlyDestinations() {
		for (int selected = 0; selected < 3; selected++) {
			for (int path = 0; path < 3; path++) {
				Planes frame = new Planes(3, 3);
				frame.planes[selected] = frame.planes[selected].asReadOnlyBuffer();
				Packed packed = new Packed(path, 3 * 3 * 4);
				RuntimeException failure = assertThrows(RuntimeException.class, () -> convert(frame, packed, true));
				assertTrue(failure.getMessage().contains("read-only"));
				frame.assertUntouched();
			}
		}
	}

	@Test
	void readsReadOnlyPlanes() throws Exception {
		for (int path = 0; path < 3; path++) {
			Planes frame = new Planes(3, 3);
			Packed expected = new Packed(path, 3 * 3 * 4);
			convert(frame, expected, false);
			for (int plane = 0; plane < 3; plane++) {
				frame.planes[plane] = frame.planes[plane].asReadOnlyBuffer();
			}
			Packed actual = new Packed(path, 3 * 3 * 4);
			convert(frame, actual, false);
			assertArrayEquals(expected.bytes(), actual.bytes());
			frame.assertUntouched();
		}
	}

	@Test
	void acceptsExactPaddedPlanes() throws Exception {
		for (int[] size : new int[][] { { 1, 1 }, { 3, 1 }, { 1, 3 }, { 3, 3 }, { 4, 4 } }) {
			for (int path = 0; path < 3; path++) {
				Planes frame = new Planes(size[0], size[1]);
				Packed packed = new Packed(path, size[0] * size[1] * 4);
				convert(frame, packed, true);
				frame.assertPaddingUntouched();
				Packed actual = new Packed(path, packed.bytes().length);
				convert(frame, actual, false);
				frame.assertPaddingUntouched();
				// All input RGB channels have the same constant value.
				for (int pixel = 0; pixel < size[0] * size[1]; pixel++) {
					byte[] bytes = actual.bytes();
					for (int channel = 0; channel < 3; channel++) {
						assertTrue(Math.abs((bytes[pixel * 4 + channel] & 0xff) - Packed.VALUE) <= 2);
					}
				}
			}
		}
	}

	private static void convert(Planes planes, Packed packed, boolean destination) throws Exception {
		if (destination) {
			if (packed.buffer == null) VideoBufferConverter.convertToI420(packed.array, planes, FourCC.ARGB);
			else VideoBufferConverter.convertToI420(packed.buffer, planes, FourCC.ARGB);
		}
		else {
			if (packed.buffer == null) VideoBufferConverter.convertFromI420(planes, packed.array, FourCC.ARGB);
			else VideoBufferConverter.convertFromI420(planes, packed.buffer, FourCC.ARGB);
		}
	}

	private static class Packed {
		static final int VALUE = 90;
		final byte[] array;
		final ByteBuffer buffer;

		Packed(int storage, int size) {
			array = new byte[size];
			Arrays.fill(array, (byte) VALUE);
			buffer = storage == 0 ? null : storage == 1 ? ByteBuffer.allocate(size) : ByteBuffer.allocateDirect(size);
			if (buffer != null) buffer.put(array).rewind();
		}

		byte[] bytes() {
			if (buffer == null) return array;
			byte[] result = new byte[array.length];
			buffer.duplicate().get(result);
			return result;
		}

		void assertUntouched() {
			for (byte value : bytes()) assertEquals((byte) VALUE, value);
		}
	}

	/** Views have exact capacities; larger guarded allocations keep a regression probe safe. */
	private static class Planes implements I420Buffer {
		static final byte GUARD = 0x5a;
		static final int OFFSET = 16;
		final int width;
		final int height;
		final int[] strides;
		final ByteBuffer[] whole = new ByteBuffer[3];
		final ByteBuffer[] planes = new ByteBuffer[3];

		Planes(int width, int height) {
			this.width = width;
			this.height = height;
			strides = new int[] { width + 2, (width + 1) / 2 + 1, (width + 1) / 2 + 1 };
			for (int plane = 0; plane < 3; plane++) {
				int columns = plane == 0 ? width : (width + 1) / 2;
				int rows = plane == 0 ? height : (height + 1) / 2;
				int required = strides[plane] * (rows - 1) + columns;
				whole[plane] = ByteBuffer.allocateDirect(128);
				for (int i = 0; i < whole[plane].capacity(); i++) whole[plane].put(i, GUARD);
				ByteBuffer window = whole[plane].duplicate();
				window.position(OFFSET);
				window.limit(OFFSET + required);
				planes[plane] = window.slice();
			}
		}

		void invalidate(int selected, String kind) {
			switch (kind) {
				case "short":
					planes[selected].limit(planes[selected].capacity() - 1);
					planes[selected] = planes[selected].slice();
					break;
				case "heap": planes[selected] = ByteBuffer.allocate(planes[selected].capacity()); break;
				case "null": planes[selected] = null; break;
				case "stride": strides[selected] = (selected == 0 ? width : (width + 1) / 2) - 1; break;
				case "zero": strides[selected] = 0; break;
				case "negative": strides[selected] = -1; break;
				case "overflow": strides[selected] = Integer.MAX_VALUE; break;
				default: throw new AssertionError(kind);
			}
		}

		void assertUntouched() {
			for (ByteBuffer buffer : whole) {
				for (int i = 0; i < buffer.capacity(); i++) assertEquals(GUARD, buffer.get(i));
			}
		}

		void assertPaddingUntouched() {
			for (int plane = 0; plane < 3; plane++) {
				int columns = plane == 0 ? width : (width + 1) / 2;
				int rows = plane == 0 ? height : (height + 1) / 2;
				for (int i = 0; i < whole[plane].capacity(); i++) {
					int index = i - OFFSET;
					if (index < 0 || index / strides[plane] >= rows || index % strides[plane] >= columns) {
						assertEquals(GUARD, whole[plane].get(i), "Plane " + plane + " byte " + i);
					}
				}
			}
		}

		@Override public int getWidth() { return width; }
		@Override public int getHeight() { return height; }
		@Override public int getStrideY() { return strides[0]; }
		@Override public int getStrideU() { return strides[1]; }
		@Override public int getStrideV() { return strides[2]; }
		@Override public ByteBuffer getDataY() { return planes[0]; }
		@Override public ByteBuffer getDataU() { return planes[1]; }
		@Override public ByteBuffer getDataV() { return planes[2]; }
		@Override public I420Buffer toI420() { return this; }
		@Override public void retain() { }
		@Override public void release() { }
		@Override public VideoFrameBuffer cropAndScale(int x, int y, int w, int h, int sw, int sh) {
			throw new UnsupportedOperationException();
		}
	}
}
