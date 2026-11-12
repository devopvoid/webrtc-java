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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.MediaStreamTrackState;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.video.desktop.TestWindowProcess;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class VideoDesktopSourceTests extends TestBase {

	private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

	@Test
	void frameRateOutOfRangeRejected() {
		VideoDesktopSource source = new VideoDesktopSource();

		try {
			// 65536 would reach the capture thread as 0, -1 as 65535.
			for (int frameRate : new int[] { Integer.MIN_VALUE, -1, 0, 1001, 65536, Integer.MAX_VALUE }) {
				assertThrows(IllegalArgumentException.class, () -> source.setFrameRate(frameRate));
			}

			assertDoesNotThrow(() -> source.setFrameRate(1));
			assertDoesNotThrow(() -> source.setFrameRate(VideoDesktopSource.MAX_FRAME_RATE));
		}
		finally {
			source.dispose();
		}
	}

	@Test
	void maxFrameSizeHoldsBothLimits() throws Exception {
		// The portal would wait for the user to pick a source.
		assumeFalse("wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE")), "Wayland");
		assumeFalse(GraphicsEnvironment.isHeadless(), "No display");

		DesktopSource screen = firstScreen();

		VideoDesktopSource source = new VideoDesktopSource();
		source.setSourceId(screen.id, false);
		// On any common screen, fitting the width leaves the height too large.
		source.setMaxFrameSize(320, 60);

		VideoTrack track = factory.createVideoTrack("screen", source);

		CompletableFuture<int[]> size = new CompletableFuture<>();

		VideoTrackSink sink = frame -> {
			size.complete(new int[] { frame.buffer.getWidth(), frame.buffer.getHeight() });
			frame.release();
		};

		int[] first;

		try {
			track.addSink(sink);
			source.start();

			first = size.completeOnTimeout(null, 10, TimeUnit.SECONDS).join();
		}
		finally {
			source.stop();
			track.removeSink(sink);
			track.dispose();
			source.dispose();
		}

		assumeTrue(first != null, "The screen delivered no frames");

		assertEquals(60, first[1]);
		assertTrue(first[0] <= 320, "Width " + first[0] + " exceeds the limit");
	}

	@Test
	void screenFramesFollowScreen() throws Exception {
		assumeTrue(OS.contains("mac"), "covers the macOS screen capturers");
		assumeFalse(GraphicsEnvironment.isHeadless(), "No display");

		DesktopSource screen = firstScreen();

		// A window of our own, switched between black and white. A capture
		// that froze on its first frame keeps showing one of the two, while
		// still delivering frames (issue #344).
		Frame window = new Frame();
		Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
				.getDefaultScreenDevice().getDefaultConfiguration().getBounds();

		EventQueue.invokeAndWait(() -> {
			window.setUndecorated(true);
			window.setAlwaysOnTop(true);
			window.setBackground(Color.BLACK);
			window.setBounds(bounds.x + bounds.width / 10, bounds.y + bounds.height / 10,
					bounds.width * 8 / 10, bounds.height * 8 / 10);
			window.setVisible(true);
			window.toFront();
		});

		VideoDesktopSource source = new VideoDesktopSource();
		source.setSourceId(screen.id, false);
		source.setFrameRate(10);
		source.setMaxFrameSize(320, 240);

		VideoTrack track = factory.createVideoTrack("screen", source);

		List<Double> luma = new ArrayList<>();

		VideoTrackSink sink = frame -> {
			NativeI420Buffer buffer = (NativeI420Buffer) frame.buffer;
			double mean = meanLuma(buffer);

			frame.release();

			synchronized (luma) {
				luma.add(mean);
			}
		};

		try {
			track.addSink(sink);
			source.start();

			// Let the capture start on a black window, then turn it white
			// and back, a few times.
			Thread.sleep(1000);

			for (int i = 0; i < 6; i++) {
				Color color = i % 2 == 0 ? Color.WHITE : Color.BLACK;

				EventQueue.invokeAndWait(() -> {
					window.setBackground(color);
					window.repaint();
				});

				Thread.sleep(500);
			}
		}
		finally {
			source.stop();
			track.removeSink(sink);
			track.dispose();
			source.dispose();

			EventQueue.invokeAndWait(window::dispose);
		}

		List<Double> means;

		synchronized (luma) {
			means = new ArrayList<>(luma);
		}

		assumeFalse(means.isEmpty(), "The screen delivered no frames");

		double min = means.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
		double max = means.stream().mapToDouble(Double::doubleValue).max().getAsDouble();

		// The window covers about two thirds of the screen, so white and black
		// differ by more than 100 in mean luma.
		assertTrue(max - min > 50,
				"Frames did not follow the screen: " + means.size() + " frames, mean luma "
						+ String.format(Locale.ROOT, "%.1f to %.1f", min, max));
	}

	@Test
	void windowFramesFollowWindow() throws Exception {
		assumeTrue(OS.contains("mac"), "covers the macOS window capturers");
		assumeFalse(GraphicsEnvironment.isHeadless(), "No display");

		double scale = screenScale();

		// A window of another process, which ScreenCaptureKit captures on
		// macOS 14 and newer. This process's own windows are captured with
		// CGWindowListCreateImage.
		try (TestWindowProcess window = TestWindowProcess.open(400, 300)) {
			DesktopSource source = window.find();

			// Without screen recording permission, another process's windows
			// are listed without their titles.
			assumeTrue(source != null, "The window is not listed under its title");

			try (WindowCapture capture = new WindowCapture(source)) {
				int[] first = capture.await(frame -> true, 5000);

				assumeTrue(first != null, "The window delivered no frames");
				// Black but for the title bar, unless the content is hidden
				// for want of permission.
				assumeTrue(first[2] < 80, "The window's content is not visible");

				assertSize(400, 300, scale, first);

				window.setColor(true);

				int[] white = capture.await(frame -> frame[2] > 150, 3000);

				assertTrue(white[2] > 150, "Frames did not follow the window turning white: luma " + white[2]);

				window.setSize(800, 500);
				assertSize(800, 500, scale, capture.await(frame -> hasSize(800, 500, scale, frame), 3000));

				window.setSize(300, 250);
				assertSize(300, 250, scale, capture.await(frame -> hasSize(300, 250, scale, frame), 3000));
			}
		}
	}

	@Test
	void closedWindowStopsCapture() throws Exception {
		assumeTrue(OS.contains("mac"), "covers the macOS window capturers");
		assumeFalse(GraphicsEnvironment.isHeadless(), "No display");

		try (TestWindowProcess window = TestWindowProcess.open(400, 300)) {
			DesktopSource source = window.find();

			assumeTrue(source != null, "The window is not listed under its title");

			try (WindowCapture capture = new WindowCapture(source)) {
				assumeTrue(capture.await(frame -> true, 5000) != null, "The window delivered no frames");

				window.closeWindow();

				// Capture stops within a frame or two of the window closing.
				Thread.sleep(1000);

				int frames = capture.frames();

				Thread.sleep(1000);

				assertEquals(frames, capture.frames(), "Frames kept coming after the window closed");
			}
		}
	}

	@Test
	void ownWindowCapturedUntilClosed() throws Exception {
		assumeTrue(OS.contains("mac"), "covers the macOS window capturers");
		assumeFalse(GraphicsEnvironment.isHeadless(), "No display");

		double scale = screenScale();

		// This process captures its own window with CGWindowListCreateImage,
		// which needs no screen recording permission, and closing the window
		// must end the capture. Its content is not checked: a window of a
		// process that is not in front may not be redrawn in time.
		String title = "webrtc-java " + UUID.randomUUID();
		Frame window = new Frame(title);

		EventQueue.invokeAndWait(() -> {
			window.setBackground(Color.BLACK);
			window.setBounds(100, 100, 400, 300);
			window.setVisible(true);
		});

		try {
			DesktopSource source = TestWindowProcess.findWindow(title);

			assumeTrue(source != null, "The window is not listed under its title");

			try (WindowCapture capture = new WindowCapture(source)) {
				int[] first = capture.await(frame -> true, 5000);

				assumeTrue(first != null, "The window delivered no frames");

				assertSize(400, 300, scale, first);

				EventQueue.invokeAndWait(window::dispose);

				Thread.sleep(1000);

				int frames = capture.frames();

				Thread.sleep(1000);

				assertEquals(frames, capture.frames(), "Frames kept coming after the window closed");
			}
		}
		finally {
			EventQueue.invokeAndWait(window::dispose);
		}
	}

	@Test
	void fullDesktopCoversAllScreens() throws Exception {
		assumeTrue(OS.contains("mac"), "covers the macOS screen capturers");
		assumeFalse(GraphicsEnvironment.isHeadless(), "No display");

		// The desktop, as WebRTC's CGDisplayStream capturer composes it: the
		// bounds of all screens, in pixels. ScreenCaptureKit captures single
		// screens only, and would deliver just the first.
		Rectangle desktop = new Rectangle();
		double scale = 0;

		for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
			GraphicsConfiguration config = device.getDefaultConfiguration();
			double deviceScale = config.getDefaultTransform().getScaleX();

			assumeTrue(scale == 0 || scale == deviceScale, "The screens differ in scale");

			scale = deviceScale;
			desktop = desktop.isEmpty() ? config.getBounds() : desktop.union(config.getBounds());
		}

		VideoDesktopSource source = new VideoDesktopSource();
		source.setSourceId(-1, false);
		source.setFrameRate(10);

		VideoTrack track = factory.createVideoTrack("desktop", source);

		CompletableFuture<int[]> size = new CompletableFuture<>();

		VideoTrackSink sink = frame -> {
			size.complete(new int[] { frame.buffer.getWidth(), frame.buffer.getHeight() });
			frame.release();
		};

		int[] first;

		try {
			track.addSink(sink);
			source.start();

			first = size.completeOnTimeout(null, 10, TimeUnit.SECONDS).join();
		}
		finally {
			source.stop();
			track.removeSink(sink);
			track.dispose();
			source.dispose();
		}

		assumeTrue(first != null, "The desktop delivered no frames");

		assertEquals(Math.round(desktop.width * scale) + "x" + Math.round(desktop.height * scale),
				first[0] + "x" + first[1]);
	}

	@Test
	void retainedFrameKeepsPixels() throws Exception {
		assumeFalse(isWaylandSession(), "Starting a capture asks the user what to share");
		assumeFalse(GraphicsEnvironment.isHeadless(), "No display");

		DesktopSource screen = firstScreen();

		// A window of our own, to change the screen while a frame is retained.
		Frame window = new Frame();
		Rectangle bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
				.getDefaultScreenDevice().getDefaultConfiguration().getBounds();

		EventQueue.invokeAndWait(() -> {
			window.setUndecorated(true);
			window.setAlwaysOnTop(true);
			window.setBackground(Color.BLACK);
			window.setBounds(bounds.x + bounds.width / 10, bounds.y + bounds.height / 10,
					bounds.width * 8 / 10, bounds.height * 8 / 10);
			window.setVisible(true);
			window.toFront();
		});

		// No maximum size: only frames that are not scaled were shared.
		VideoDesktopSource source = new VideoDesktopSource();
		source.setSourceId(screen.id, false);
		source.setFrameRate(10);

		VideoTrack track = factory.createVideoTrack("screen", source);

		AtomicReference<VideoFrame> retained = new AtomicReference<>();
		AtomicReference<ByteBuffer> original = new AtomicReference<>();
		CountDownLatch firstFrame = new CountDownLatch(1);
		CountDownLatch changedFrame = new CountDownLatch(1);

		VideoProcessor processor = new VideoProcessor() {
			@Override
			public void setSink(VideoTrackSink sink) {
			}

			@Override
			public void onFrameCaptured(VideoFrame frame) {
				ByteBuffer pixels = ((NativeI420Buffer) frame.buffer).getDataY();

				if (retained.get() == null) {
					frame.retain();

					original.set(copyOf(pixels));
					retained.set(frame);
					firstFrame.countDown();
				}
				else if (!original.get().equals(pixels)) {
					changedFrame.countDown();
				}
			}
		};

		// Without a sink the source drops its frames.
		VideoTrackSink sink = VideoFrame::release;

		try {
			source.setVideoProcessor(processor);
			track.addSink(sink);
			source.start();

			assumeTrue(firstFrame.await(10, TimeUnit.SECONDS), "The screen delivered no frames");

			EventQueue.invokeAndWait(() -> {
				window.setBackground(Color.WHITE);
				window.repaint();
			});

			assumeTrue(changedFrame.await(10, TimeUnit.SECONDS), "The screen did not change");

			source.stop();

			assertEquals(original.get(), ((NativeI420Buffer) retained.get().buffer).getDataY(),
					"The next capture changed the pixels of a retained frame");
		}
		finally {
			source.stop();
			track.removeSink(sink);
			track.dispose();
			source.dispose();

			if (retained.get() != null) {
				retained.get().release();
			}

			EventQueue.invokeAndWait(window::dispose);
		}
	}

	@Test
	void missingBackendEndsTrack() {
		assumeFalse(isWaylandSession(), "Starting a capture asks the user what to share");
		assumeTrue(hasNoBackend(), "A capture backend is available");

		VideoDesktopSource source = new VideoDesktopSource();
		VideoTrack track = factory.createVideoTrack("screen", source);

		AtomicInteger ended = new AtomicInteger();

		track.addTrackEndedListener(t -> ended.incrementAndGet());

		try {
			source.start();
			// Waits for the capture thread, which has given up by then.
			source.stop();

			assertEquals(MediaStreamTrackState.ENDED, track.getState());
			assertEquals(1, ended.get());
		}
		finally {
			track.dispose();
			source.dispose();
		}
	}

	/**
	 * Captures one window, keeping the width, height and mean luma of the
	 * latest frame.
	 */
	private final class WindowCapture implements AutoCloseable {

		private final VideoDesktopSource source = new VideoDesktopSource();

		private final VideoTrack track;

		private final VideoTrackSink sink;

		private final AtomicInteger frames = new AtomicInteger();

		private final AtomicReference<int[]> latest = new AtomicReference<>();


		WindowCapture(DesktopSource window) {
			source.setSourceId(window.id, true);
			source.setFrameRate(10);

			track = factory.createVideoTrack("window", source);
			sink = frame -> {
				NativeI420Buffer buffer = (NativeI420Buffer) frame.buffer;

				latest.set(new int[] { buffer.getWidth(), buffer.getHeight(), (int) sampledLuma(buffer) });
				frame.release();
				frames.incrementAndGet();
			};

			track.addSink(sink);
			source.start();
		}

		/**
		 * Waits for a frame that meets the condition. Returns it, or else the
		 * latest frame, which is null if none came.
		 */
		int[] await(Predicate<int[]> condition, long millis) throws InterruptedException {
			long deadline = System.currentTimeMillis() + millis;
			int[] frame = latest.get();

			while ((frame == null || !condition.test(frame)) && System.currentTimeMillis() < deadline) {
				Thread.sleep(50);

				frame = latest.get();
			}

			return frame;
		}

		int frames() {
			return frames.get();
		}

		@Override
		public void close() {
			source.stop();
			track.removeSink(sink);
			track.dispose();
			source.dispose();
		}
	}

	private static double screenScale() {
		return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
				.getDefaultConfiguration().getDefaultTransform().getScaleX();
	}

	private static boolean hasSize(int width, int height, double scale, int[] frame) {
		return Math.abs(frame[0] - Math.round(width * scale)) <= 2
				&& Math.abs(frame[1] - Math.round(height * scale)) <= 2;
	}

	private static void assertSize(int width, int height, double scale, int[] frame) {
		assertTrue(frame != null && hasSize(width, height, scale, frame),
				"Expected a frame of a " + width + "x" + height + " window at scale " + scale + ", got "
						+ (frame == null ? "none" : frame[0] + "x" + frame[1]));
	}

	/** The mean luma of every fourth pixel in every fourth row. */
	private static double sampledLuma(NativeI420Buffer buffer) {
		ByteBuffer data = buffer.getDataY();
		int stride = buffer.getStrideY();
		long sum = 0;
		long count = 0;

		for (int y = 0; y < buffer.getHeight(); y += 4) {
			for (int x = 0; x < buffer.getWidth(); x += 4) {
				sum += data.get(y * stride + x) & 0xFF;
				count++;
			}
		}

		return (double) sum / count;
	}

	private static ByteBuffer copyOf(ByteBuffer buffer) {
		ByteBuffer copy = ByteBuffer.allocate(buffer.remaining());

		copy.put(buffer.duplicate());
		copy.flip();

		return copy;
	}

	private static boolean isWaylandSession() {
		return "wayland".equals(System.getenv("XDG_SESSION_TYPE"));
	}

	private static boolean hasNoBackend() {
		ScreenCapturer capturer = new ScreenCapturer();

		try {
			capturer.getDesktopSources();

			return false;
		}
		catch (Error | RuntimeException e) {
			// Without a display, as on headless Linux, listing fails.
			return true;
		}
		finally {
			capturer.dispose();
		}
	}

	private static double meanLuma(NativeI420Buffer buffer) {
		ByteBuffer data = buffer.getDataY();
		int stride = buffer.getStrideY();
		int width = buffer.getWidth();
		int height = buffer.getHeight();
		long sum = 0;

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				sum += data.get(y * stride + x) & 0xFF;
			}
		}

		return (double) sum / (width * height);
	}

	private static DesktopSource firstScreen() {
		ScreenCapturer capturer = new ScreenCapturer();

		try {
			List<DesktopSource> screens = capturer.getDesktopSources();

			assumeFalse(screens == null || screens.isEmpty(), "No screen to capture");

			return screens.get(0);
		}
		finally {
			capturer.dispose();
		}
	}
}
