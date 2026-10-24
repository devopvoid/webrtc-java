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

package dev.onvoid.webrtc.client.javafx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Needs a display for the JavaFX toolkit, so it skips where there is none, as
 * on CI runners.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class VideoViewTest {

	private static final long TIMEOUT_SECONDS = 15;

	private boolean toolkit;

	private WebRtc rtc;


	@BeforeAll
	void startToolkit() {
		try {
			Platform.startup(() -> { });
			Platform.setImplicitExit(false);
			toolkit = true;
		}
		catch (IllegalStateException alreadyStarted) {
			toolkit = true;
		}
		catch (Throwable noDisplay) {
			toolkit = false;
		}

		// Also loads the native library the frames need.
		rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build();
	}

	@AfterAll
	void stop() {
		rtc.close();
	}

	@BeforeEach
	void needsToolkit() {
		assumeTrue(toolkit, "Needs a display for the JavaFX toolkit");
	}

	@Test
	void showsFrame() throws Exception {
		VideoView view = onFx(VideoView::new);
		assertNull(onFx(view::snapshot));

		view.accept(frame(64, 48, 128, 128));

		Image snapshot = presented(view);
		assertEquals(64, (int) snapshot.getWidth());
		assertEquals(48, (int) snapshot.getHeight());
		assertGray(snapshot.getPixelReader().getColor(32, 24));

		onFx(() -> {
			view.clear();
			return null;
		});
		assertNull(onFx(view::snapshot));
	}

	@Test
	void keepsAspectRatio() throws Exception {
		VideoView view = sized(200, 100);
		view.accept(frame(64, 48, 128, 128));
		presented(view);

		// A 4:3 picture in a 2:1 view leaves bars on the sides.
		Image painted = paint(view);
		assertEquals(Color.BLACK, painted.getPixelReader().getColor(5, 50));
		assertGray(painted.getPixelReader().getColor(100, 50));
	}

	@Test
	void mirrors() throws Exception {
		VideoView view = sized(64, 48);
		view.accept(frame(64, 48, 16, 235));
		presented(view);

		assertTrue(paint(view).getPixelReader().getColor(8, 24).getBrightness() < 0.25);

		onFx(() -> {
			view.setMirrored(true);
			return null;
		});

		// The bright right half is now on the left.
		assertTrue(paint(view).getPixelReader().getColor(8, 24).getBrightness() > 0.75);
	}

	@Test
	void showsRemoteVideo() throws Exception {
		SignalingPair link = Signaling.loopback();
		Peer caller = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
		Peer callee = rtc.createPeer(PeerOptions.builder().signaling(link.second()).polite(true).build());
		CustomVideo video = rtc.media().customVideo("video");
		VideoView view = onFx(VideoView::new);

		callee.onTrack(track -> track.asVideo().onFrame(view));
		caller.addTrack(video);

		ScheduledExecutorService feeder = Executors.newSingleThreadScheduledExecutor();

		try {
			feeder.scheduleAtFixedRate(() -> {
				VideoFrame frame = frame(320, 240, 128, 128);
				video.push(frame);
				frame.release();
			}, 0, 33, TimeUnit.MILLISECONDS);

			assertEquals(320, (int) presented(view).getWidth());
		}
		finally {
			feeder.shutdownNow();
			caller.close();
			callee.close();
			video.close();
		}
	}

	private VideoView sized(int width, int height) throws Exception {
		return onFx(() -> {
			VideoView view = new VideoView();
			new Scene(view, width, height);
			view.resize(width, height);
			view.layout();
			return view;
		});
	}

	/** Waits until the view has taken a frame over and returns its picture. */
	private static Image presented(VideoView view) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (System.nanoTime() < deadline) {
			Image snapshot = onFx(view::snapshot);

			if (snapshot != null) {
				return snapshot;
			}

			Thread.sleep(20);
		}

		throw new AssertionError("No frame reached the view");
	}

	private static Image paint(VideoView view) throws Exception {
		return onFx(() -> view.snapshot(null, null));
	}

	private static <T> T onFx(Callable<T> action) throws Exception {
		CompletableFuture<T> result = new CompletableFuture<>();

		Platform.runLater(() -> {
			try {
				result.complete(action.call());
			}
			catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});

		return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	/**
	 * A frame whose left half has luma {@code left} and right half {@code right}, with
	 * neutral chroma.
	 */
	private static VideoFrame frame(int width, int height, int left, int right) {
		NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
		ByteBuffer y = buffer.getDataY();

		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				y.put(row * buffer.getStrideY() + column, (byte) (column < width / 2 ? left : right));
			}
		}

		fill(buffer.getDataU());
		fill(buffer.getDataV());

		return new VideoFrame(buffer, System.nanoTime());
	}

	private static void fill(ByteBuffer plane) {
		for (int i = 0; i < plane.capacity(); i++) {
			plane.put(i, (byte) 128);
		}
	}

	private static void assertGray(Color color) {
		assertNotNull(color);
		assertTrue(Math.abs(color.getRed() - color.getGreen()) < 0.04 && Math.abs(color.getGreen() - color.getBlue()) < 0.04
				&& color.getGreen() > 0.4 && color.getGreen() < 0.63, () -> "Not mid gray: " + color);
	}

}
