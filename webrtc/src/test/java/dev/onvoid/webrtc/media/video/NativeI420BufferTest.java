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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.Test;

class NativeI420BufferTest extends TestBase {

	@Test
	void cropOutlivesSource() {
		NativeI420Buffer source = NativeI420Buffer.allocate(8, 8);
		fillY(source);

		NativeI420Buffer cropped = assertInstanceOf(NativeI420Buffer.class,
				source.cropAndScale(2, 4, 4, 4, 4, 4));

		// The crop holds the pixels of the source, which must stay valid without it.
		source.release();

		cropped.retain();
		cropped.release();

		ByteBuffer y = cropped.getDataY();

		for (int row = 0; row < 4; row++) {
			for (int col = 0; col < 4; col++) {
				assertEquals(yValue(row + 4, col + 2), y.get(row * cropped.getStrideY() + col));
			}
		}

		// The native side reads the crop as well.
		CustomVideoSource videoSource = new CustomVideoSource();
		VideoTrack videoTrack = factory.createVideoTrack("videoTrack", videoSource);
		List<Integer> widths = new ArrayList<>();
		VideoTrackSink sink = frame -> widths.add(frame.buffer.getWidth());

		videoTrack.addSink(sink);

		VideoFrame frame = new VideoFrame(cropped, 0);
		videoSource.pushFrame(frame);
		frame.release();

		videoTrack.removeSink(sink);
		videoTrack.dispose();
		videoSource.dispose();

		assertEquals(Collections.singletonList(4), widths);
	}

	@Test
	void cropEndsAtLastPixel() {
		NativeI420Buffer source = NativeI420Buffer.allocate(8, 8);
		I420Buffer cropped = source.cropAndScale(2, 0, 4, 8, 4, 8).toI420();

		// The last row of a plane ends at the crop width, not at the stride.
		assertEquals(7 * cropped.getStrideY() + 4, cropped.getDataY().capacity());
		assertEquals(3 * cropped.getStrideU() + 2, cropped.getDataU().capacity());
		assertEquals(3 * cropped.getStrideV() + 2, cropped.getDataV().capacity());

		((NativeI420Buffer) cropped).release();
		source.release();
	}

	@Test
	void cropOutsideBuffer() {
		NativeI420Buffer source = NativeI420Buffer.allocate(8, 8);

		assertThrows(IllegalArgumentException.class, () -> source.cropAndScale(6, 0, 4, 4, 4, 4));
		assertThrows(IllegalArgumentException.class, () -> source.cropAndScale(0, -1, 4, 4, 4, 4));
		assertThrows(IllegalArgumentException.class, () -> source.cropAndScale(0, 0, 0, 4, 0, 4));
		assertThrows(IllegalArgumentException.class, () -> source.cropAndScale(0, 0, 4, 4, 0, 2));

		source.release();
	}

	@Test
	void allocateInvalidSize() {
		assertThrows(IllegalArgumentException.class, () -> NativeI420Buffer.allocate(0, 8));
		assertThrows(IllegalArgumentException.class, () -> NativeI420Buffer.allocate(8, 0));
		assertThrows(IllegalArgumentException.class, () -> NativeI420Buffer.allocate(-8, 8));
		assertThrows(IllegalArgumentException.class, () -> NativeI420Buffer.allocate(8, Integer.MIN_VALUE));
	}

	@Test
	void allocateTooLarge() {
		// The planes would exceed what WebRTC can address with an int.
		assertThrows(IllegalArgumentException.class, () -> NativeI420Buffer.allocate(Integer.MAX_VALUE, Integer.MAX_VALUE));
		assertThrows(IllegalArgumentException.class, () -> NativeI420Buffer.allocate(46341, 46341));
		assertThrows(IllegalArgumentException.class, () -> NativeI420Buffer.allocate(Integer.MAX_VALUE, 1));
	}

	@Test
	void allocateOddSize() {
		NativeI420Buffer buffer = NativeI420Buffer.allocate(3, 1);

		assertEquals(3, buffer.getWidth());
		assertEquals(1, buffer.getHeight());
		assertEquals(3, buffer.getDataY().capacity());
		assertEquals(2, buffer.getDataU().capacity());
		assertEquals(2, buffer.getDataV().capacity());

		buffer.release();
	}

	@Test
	void pushReleasedBuffer() {
		CustomVideoSource videoSource = new CustomVideoSource();
		NativeI420Buffer buffer = NativeI420Buffer.allocate(8, 8);

		buffer.release();

		VideoFrame frame = new VideoFrame(buffer, 0);

		assertThrows(RuntimeException.class, () -> videoSource.pushFrame(frame));

		videoSource.dispose();
	}

	private static void fillY(I420Buffer buffer) {
		ByteBuffer y = buffer.getDataY();

		for (int row = 0; row < buffer.getHeight(); row++) {
			for (int col = 0; col < buffer.getWidth(); col++) {
				y.put(row * buffer.getStrideY() + col, yValue(row, col));
			}
		}
	}

	private static byte yValue(int row, int col) {
		return (byte) (row * 16 + col);
	}
}
