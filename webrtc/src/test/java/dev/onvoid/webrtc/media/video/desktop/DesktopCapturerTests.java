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

import java.awt.GraphicsEnvironment;
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

	/**
	 * Starting a capturer in a Wayland session asks the user what to share.
	 */
	private static void assumeNoPortalDialog() {
		assumeFalse("wayland".equals(System.getenv("XDG_SESSION_TYPE")), "Wayland session");
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
