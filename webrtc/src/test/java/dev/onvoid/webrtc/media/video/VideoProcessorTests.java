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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class VideoProcessorTests extends TestBase {

	@Test
	void customSourceRejectsProcessor() {
		CustomVideoSource source = new CustomVideoSource();

		try {
			assertThrows(UnsupportedOperationException.class,
					() -> source.setVideoProcessor(new TestProcessor()));
		}
		finally {
			source.dispose();
		}
	}

	@Test
	void setAndRemove() {
		VideoTrackSource[] sources = { new VideoDesktopSource(), new VideoDeviceSource() };

		for (VideoTrackSource source : sources) {
			TestProcessor processor = new TestProcessor();

			source.setVideoProcessor(processor);

			assertNotNull(processor.sink, "setSink was not called");

			source.setVideoProcessor(new TestProcessor());
			source.setVideoProcessor(null);
		}

		((VideoDesktopSource) sources[0]).dispose();
		((VideoDeviceSource) sources[1]).dispose();
	}

	@Test
	void staleSinkDropsFrames() {
		VideoDesktopSource source = new VideoDesktopSource();
		TestProcessor first = new TestProcessor();
		TestProcessor second = new TestProcessor();

		source.setVideoProcessor(first);
		source.setVideoProcessor(second);

		VideoFrame frame = new VideoFrame(NativeI420Buffer.allocate(64, 48), 0);

		try {
			// The replaced processor's sink, and after disposing both sinks,
			// accept frames without passing them on or failing.
			first.sink.onVideoFrame(frame);

			source.dispose();

			first.sink.onVideoFrame(frame);
			second.sink.onVideoFrame(frame);

			assertThrows(NullPointerException.class, () -> second.sink.onVideoFrame(null));
		}
		finally {
			frame.release();
		}
	}

	@Test
	void acceptsCustomBufferLayouts() {
		VideoDesktopSource source = new VideoDesktopSource();
		TestProcessor processor = new TestProcessor();

		source.setVideoProcessor(processor);

		try {
			// Tightly packed, odd-sized, and padded rows whose last row ends at the width.
			processor.sink.onVideoFrame(new VideoFrame(new LaidOutI420Buffer(64, 48), 0));
			processor.sink.onVideoFrame(new VideoFrame(new LaidOutI420Buffer(5, 3), 0));
			processor.sink.onVideoFrame(new VideoFrame(new LaidOutI420Buffer(64, 48,
					80, 80 * 47 + 64, 40, 40 * 23 + 32, 48, 48 * 23 + 32), 0));
		}
		finally {
			source.dispose();
		}
	}

	@Test
	void rejectsCustomBufferOverreads() {
		VideoDesktopSource source = new VideoDesktopSource();
		TestProcessor processor = new TestProcessor();

		source.setVideoProcessor(processor);

		int w = 64;
		int h = 48;
		int cw = 32;
		int ch = 24;

		// Each would make the copy read past the end of a plane.
		LaidOutI420Buffer[] buffers = {
				new LaidOutI420Buffer(w, h, w - 1, w * h, cw, cw * ch, cw, cw * ch),
				new LaidOutI420Buffer(w, h, -w, w * h, cw, cw * ch, cw, cw * ch),
				new LaidOutI420Buffer(w, h, w, w * h, cw - 1, cw * ch, cw, cw * ch),
				new LaidOutI420Buffer(w, h, w, w * h, cw, cw * ch, 0, cw * ch),
				new LaidOutI420Buffer(w, h, w, w * h - 1, cw, cw * ch, cw, cw * ch),
				new LaidOutI420Buffer(w, h, w * 2, w * h, cw, cw * ch, cw, cw * ch),
				new LaidOutI420Buffer(w, h, w, w * h, cw, cw * ch - 1, cw, cw * ch),
				new LaidOutI420Buffer(w, h, w, w * h, cw, cw * ch, cw, cw * (ch - 1)),
				new LaidOutI420Buffer(w, h, w, w * h, Integer.MAX_VALUE, cw * ch, cw, cw * ch),
				// Larger than WebRTC can allocate, with planes that claim to fit.
				new LaidOutI420Buffer(46341, 46341, 46341, 16, 23171, 16, 23171, 16),
				new LaidOutI420Buffer(Integer.MAX_VALUE, 1, Integer.MAX_VALUE, 16, Integer.MAX_VALUE, 16, Integer.MAX_VALUE, 16),
		};

		try {
			for (LaidOutI420Buffer buffer : buffers) {
				VideoFrame frame = new VideoFrame(buffer, 0);

				assertThrows(RuntimeException.class, () -> processor.sink.onVideoFrame(frame),
						() -> buffer.toString());
			}
		}
		finally {
			source.dispose();
		}
	}

	@Test
	void processedDesktopFramesReachTrack() throws Exception {
		DesktopSource screen = firstScreen();

		VideoDesktopSource source = new VideoDesktopSource();
		source.setSourceId(screen.id, false);
		source.setFrameRate(10);
		source.setMaxFrameSize(320, 240);

		VideoTrack track = factory.createVideoTrack("screen", source);

		AtomicInteger processed = new AtomicInteger();
		AtomicReference<Integer> receivedLuma = new AtomicReference<>();
		AtomicReference<String> size = new AtomicReference<>();
		CountDownLatch received = new CountDownLatch(3);

		// Replaces every frame with a uniformly grey one of the same size.
		VideoProcessor processor = new VideoProcessor() {
			private VideoTrackSink sink;

			@Override
			public void setSink(VideoTrackSink sink) {
				this.sink = sink;
			}

			@Override
			public void onFrameCaptured(VideoFrame frame) {
				processed.incrementAndGet();

				NativeI420Buffer captured = (NativeI420Buffer) frame.buffer;
				NativeI420Buffer grey = NativeI420Buffer.allocate(captured.getWidth(), captured.getHeight());

				fill(grey.getDataY(), (byte) 200);
				fill(grey.getDataU(), (byte) 128);
				fill(grey.getDataV(), (byte) 128);

				VideoFrame output = new VideoFrame(grey, frame.rotation, frame.timestampNs);

				sink.onVideoFrame(output);

				output.release();
			}
		};

		VideoTrackSink trackSink = frame -> {
			NativeI420Buffer buffer = (NativeI420Buffer) frame.buffer;

			receivedLuma.set(buffer.getDataY().get(0) & 0xFF);
			size.set(buffer.getWidth() + "x" + buffer.getHeight());

			frame.release();
			received.countDown();
		};

		try {
			source.setVideoProcessor(processor);
			track.addSink(trackSink);
			source.start();

			boolean gotFrames = received.await(10, TimeUnit.SECONDS);

			assumeTrue(gotFrames || processed.get() > 0, "The screen delivered no frames");
			assertTrue(gotFrames, "Processed frames did not reach the track");

			assertEquals(200, (int) receivedLuma.get());
			assertNotNull(size.get());
		}
		finally {
			source.stop();
			track.removeSink(trackSink);
			track.dispose();
			source.dispose();
		}
	}

	@Test
	void processorCanDropFrames() throws Exception {
		DesktopSource screen = firstScreen();

		VideoDesktopSource source = new VideoDesktopSource();
		source.setSourceId(screen.id, false);
		source.setFrameRate(10);
		source.setMaxFrameSize(320, 240);

		VideoTrack track = factory.createVideoTrack("screen", source);

		CountDownLatch processed = new CountDownLatch(3);
		AtomicInteger received = new AtomicInteger();

		VideoTrackSink trackSink = frame -> {
			frame.release();
			received.incrementAndGet();
		};

		try {
			source.setVideoProcessor(new VideoProcessor() {
				@Override
				public void setSink(VideoTrackSink sink) {
				}

				@Override
				public void onFrameCaptured(VideoFrame frame) {
					// Delivers nothing.
					processed.countDown();
				}
			});
			track.addSink(trackSink);
			source.start();

			assumeTrue(processed.await(10, TimeUnit.SECONDS), "The screen delivered no frames");

			assertEquals(0, received.get());
		}
		finally {
			source.stop();
			track.removeSink(trackSink);
			track.dispose();
			source.dispose();
		}
	}

	private static DesktopSource firstScreen() {
		ScreenCapturer capturer = new ScreenCapturer();

		try {
			List<DesktopSource> screens;

			try {
				screens = capturer.getDesktopSources();
			}
			catch (Error | RuntimeException e) {
				// Without a display, as on headless Linux, listing fails.
				screens = null;
			}

			assumeFalse(screens == null || screens.isEmpty(), "No screen to capture");

			return screens.get(0);
		}
		finally {
			capturer.dispose();
		}
	}

	private static void fill(ByteBuffer buffer, byte value) {
		while (buffer.hasRemaining()) {
			buffer.put(value);
		}
	}



	/**
	 * An I420 buffer in Java with the strides and plane sizes it is given.
	 */
	private static class LaidOutI420Buffer implements I420Buffer {

		private final int width;
		private final int height;
		private final int strideY;
		private final int strideU;
		private final int strideV;
		private final ByteBuffer dataY;
		private final ByteBuffer dataU;
		private final ByteBuffer dataV;


		LaidOutI420Buffer(int width, int height) {
			this(width, height, width, width * height, (width + 1) / 2,
					((width + 1) / 2) * ((height + 1) / 2), (width + 1) / 2,
					((width + 1) / 2) * ((height + 1) / 2));
		}

		LaidOutI420Buffer(int width, int height, int strideY, int sizeY, int strideU, int sizeU,
				int strideV, int sizeV) {
			this.width = width;
			this.height = height;
			this.strideY = strideY;
			this.strideU = strideU;
			this.strideV = strideV;
			this.dataY = ByteBuffer.allocateDirect(sizeY);
			this.dataU = ByteBuffer.allocateDirect(sizeU);
			this.dataV = ByteBuffer.allocateDirect(sizeV);
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

		@Override
		public String toString() {
			return String.format("%dx%d, Y %d/%d, U %d/%d, V %d/%d", width, height,
					strideY, dataY.capacity(), strideU, dataU.capacity(), strideV, dataV.capacity());
		}
	}

	private static class TestProcessor implements VideoProcessor {

		VideoTrackSink sink;


		@Override
		public void setSink(VideoTrackSink sink) {
			this.sink = sink;
		}

		@Override
		public void onFrameCaptured(VideoFrame frame) {
			sink.onVideoFrame(frame);
		}
	}
}
