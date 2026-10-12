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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

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
class VideoDesktopSourceTests extends TestBase {

	private static final String OS = System.getProperty("os.name").toLowerCase(Locale.ROOT);

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
