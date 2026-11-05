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
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.TestBase;

import java.util.ArrayList;
import java.util.List;

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
