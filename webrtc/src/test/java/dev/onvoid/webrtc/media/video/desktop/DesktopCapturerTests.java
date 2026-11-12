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

package dev.onvoid.webrtc.media.video.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.video.I420Buffer;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class DesktopCapturerTests extends TestBase {

	@Test
	void secondStartIsRejected() {
		assumeNoPortalDialog();

		ScreenCapturer capturer = new ScreenCapturer();

		try {
			capturer.start((result, frame) -> { });

			assertThrows(IllegalStateException.class, () -> capturer.start((result, frame) -> { }));
		}
		finally {
			capturer.dispose();
		}
	}

	@Test
	void captureBeforeStartIsRejected() {
		ScreenCapturer capturer = new ScreenCapturer();

		try {
			assertThrows(IllegalStateException.class, capturer::captureFrame);
		}
		finally {
			capturer.dispose();
		}
	}

	@Test
	void missingBackendReportsPermanentError() {
		ScreenCapturer capturer = new ScreenCapturer();

		try {
			assumeTrue(hasNoBackend(capturer), "A capture backend is available");

			List<DesktopCapturer.Result> results = new ArrayList<>();

			capturer.start((result, frame) -> results.add(result));
			capturer.captureFrame();

			assertEquals(List.of(DesktopCapturer.Result.ERROR_PERMANENT), results);
		}
		finally {
			capturer.dispose();
		}
	}

	@Test
	void windowCapturerFollowsWindowUntilClosed() throws Exception {
		assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac"),
				"covers the macOS window capturers");
		assumeFalse(GraphicsEnvironment.isHeadless(), "No display");

		double scale = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
				.getDefaultConfiguration().getDefaultTransform().getScaleX();

		// A window of another process, which ScreenCaptureKit captures on
		// macOS 14 and newer.
		try (TestWindowProcess window = TestWindowProcess.open(400, 300)) {
			DesktopSource source = window.find();

			assumeTrue(source != null, "The window is not listed under its title");

			WindowCapturer capturer = new WindowCapturer();
			List<String> results = new ArrayList<>();

			try {
				capturer.selectSource(source);
				capturer.start((result, frame) -> {
					if (frame != null) {
						results.add(frame.buffer.getWidth() + "x" + frame.buffer.getHeight());
						frame.release();
					}
					else {
						results.add(result.name());
					}
				});

				String size = Math.round(400 * scale) + "x" + Math.round(300 * scale);

				for (int i = 0; i < 50 && !results.contains(size); i++) {
					capturer.captureFrame();
					Thread.sleep(100);
				}

				assumeTrue(results.stream().anyMatch(r -> r.contains("x")), "The window delivered no frames");
				assertTrue(results.contains(size), "No " + size + " frame of the window: " + results);

				window.closeWindow();

				for (int i = 0; i < 30 && !results.contains("ERROR_PERMANENT"); i++) {
					capturer.captureFrame();
					Thread.sleep(100);
				}

				assertEquals("ERROR_PERMANENT", results.get(results.size() - 1),
						"The closed window was still captured: " + results);
			}
			finally {
				capturer.dispose();
			}
		}
	}

	@Test
	void screenCapturerFollowsScreen() throws Exception {
		assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac"),
				"covers the macOS screen capturers");
		assumeFalse(GraphicsEnvironment.isHeadless(), "No display");

		// A window of our own on the main screen, switched between black and
		// white. A capture that froze on its first frame keeps showing one of
		// the two (issue #344).
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
		});

		ScreenCapturer capturer = new ScreenCapturer();
		List<Double> luma = new ArrayList<>();

		try {
			List<DesktopSource> screens = capturer.getDesktopSources();

			assumeFalse(screens.isEmpty(), "No screen to capture");

			// macOS lists the main screen first.
			capturer.selectSource(screens.get(0));
			capturer.start((result, frame) -> {
				if (frame != null) {
					luma.add(sampledLuma(frame.buffer.toI420()));
					frame.release();
				}
			});

			// Calls back on this thread, so the list needs no lock.
			for (int i = 0; i < 40; i++) {
				if (i % 5 == 0) {
					Color color = i / 5 % 2 == 0 ? Color.BLACK : Color.WHITE;

					EventQueue.invokeAndWait(() -> {
						window.setBackground(color);
						window.repaint();
					});
				}

				capturer.captureFrame();
				Thread.sleep(100);
			}
		}
		finally {
			capturer.dispose();

			EventQueue.invokeAndWait(window::dispose);
		}

		assumeFalse(luma.isEmpty(), "The screen delivered no frames");

		double min = luma.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
		double max = luma.stream().mapToDouble(Double::doubleValue).max().getAsDouble();

		// The window covers about two thirds of the screen.
		assertTrue(max - min > 50, "Frames did not follow the screen: " + luma.size()
				+ " frames, mean luma " + String.format(Locale.ROOT, "%.1f to %.1f", min, max));
	}

	/**
	 * Starting a capturer in a Wayland session asks the user what to share.
	 */
	private static void assumeNoPortalDialog() {
		assumeFalse("wayland".equals(System.getenv("XDG_SESSION_TYPE")), "Wayland session");
	}

	/** The mean luma of every fourth pixel in every fourth row. */
	private static double sampledLuma(I420Buffer buffer) {
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

	private static boolean hasNoBackend(DesktopCapturer capturer) {
		try {
			capturer.getDesktopSources();

			return false;
		}
		catch (Error | RuntimeException e) {
			// Without a display, as on headless Linux, listing fails.
			return true;
		}
	}
}
