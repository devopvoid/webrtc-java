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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import dev.onvoid.webrtc.media.video.I420Buffer;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;

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
		VideoView view = onFx(() -> new VideoView());
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
	void clearDropsFrameInConversion() throws Exception {
		VideoView view = onFx(() -> new VideoView());
		CountDownLatch converting = new CountDownLatch(1);
		CountDownLatch cleared = new CountDownLatch(1);
		VideoFrame held = frame(64, 48, 128, 128);
		VideoFrameBuffer buffer = new VideoFrameBuffer() {

			@Override
			public int getWidth() {
				return held.buffer.getWidth();
			}

			@Override
			public int getHeight() {
				return held.buffer.getHeight();
			}

			@Override
			public I420Buffer toI420() {
				converting.countDown();

				try {
					cleared.await();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}

				return held.buffer.toI420();
			}

			@Override
			public VideoFrameBuffer cropAndScale(int cropX, int cropY, int cropWidth, int cropHeight,
					int scaleWidth, int scaleHeight) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void retain() {
			}

			@Override
			public void release() {
			}
		};
		Thread delivery = new Thread(() -> view.accept(new VideoFrame(buffer, 0)));

		delivery.start();
		assertTrue(converting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

		onFx(() -> {
			view.clear();
			return null;
		});
		cleared.countDown();
		delivery.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

		// Runs after whatever the conversion handed to the JavaFX thread.
		assertNull(onFx(view::snapshot));

		// The next frame shows as usual.
		view.accept(frame(32, 24, 128, 128));
		assertEquals(32, (int) presented(view).getWidth());
	}

	@Test
	void rejectsInvalidSizes() throws Exception {
		VideoView view = onFx(() -> new VideoView());
		for (int[] size : new int[][] { { 0, 1 }, { 1, 0 }, { -1, -1 }, { 1, -1 }, { -1, 1 },
				{ Integer.MAX_VALUE / 4 + 1, 1 },
				{ 65536, 65536 }, { Integer.MAX_VALUE, Integer.MAX_VALUE } }) {
			VideoFrameBuffer invalid = (VideoFrameBuffer) Proxy.newProxyInstance(
					getClass().getClassLoader(), new Class<?>[] { VideoFrameBuffer.class },
					(proxy, method, args) -> {
						if (method.getName().equals("getWidth")) return size[0];
						if (method.getName().equals("getHeight")) return size[1];
						throw new AssertionError("Invalid frame reached conversion");
					});
			assertThrows(IllegalArgumentException.class,
					() -> view.accept(new VideoFrame(invalid, 0)));
		}
	}

	@Test
	void supports8kDimensions() {
		assertEquals(7680 * 4320 * 4, VideoView.frameBytes(7680, 4320));
		assertEquals((Integer.MAX_VALUE / 4) * 4, VideoView.frameBytes(Integer.MAX_VALUE / 4, 1));
	}

	@Test
	void growsBuffersOnDemand() throws Exception {
		VideoView view = onFx(VideoView::new);
		Field field = VideoView.class.getDeclaredField("buffers");
		field.setAccessible(true);
		ByteBuffer previous = null;
		for (int width : new int[] { 32, 64, 32, 64 }) {
			VideoFrame frame = frame(width, 48, 128, 128);
			try {
				view.accept(frame);
				assertEquals(width, (int) onFx(view::snapshot).getWidth());
				Object slot = ((Object[]) field.get(view))[0];
				Field data = slot.getClass().getDeclaredField("data");
				data.setAccessible(true);
				ByteBuffer current = (ByteBuffer) data.get(slot);
				onFx(() -> { view.clear(); return null; });
				if (previous != null && previous.capacity() >= width * 48 * 4) {
					assertSame(previous, current);
				}
				assertEquals(previous == null ? 32 * 48 * 4 : 64 * 48 * 4, current.capacity());
				previous = current;
			}
			finally {
				frame.release();
			}
		}
	}

	@Test
	void reusesBuffersAcrossSizes() throws Exception {
		VideoView view = onFx(() -> new VideoView());
		Set<ByteBuffer> storage = Collections.newSetFromMap(new IdentityHashMap<>());
		Field field = VideoView.class.getDeclaredField("buffers");
		field.setAccessible(true);
		for (int i = 0; i < 12; i++) {
			int width = i < 3 || i % 2 == 0 ? 64 : 32;
			VideoFrame frame = frame(width, 48, 128, 128);
			try {
				view.accept(frame);
				Image snapshot = onFx(view::snapshot);
				assertEquals(width, (int) snapshot.getWidth());
				assertGray(snapshot.getPixelReader().getColor(0, 0));
				for (Object slot : (Object[]) field.get(view)) {
					if (slot != null) {
						Field data = slot.getClass().getDeclaredField("data");
						data.setAccessible(true);
						storage.add((ByteBuffer) data.get(slot));
					}
				}
				if (i >= 6) onFx(() -> { view.clear(); return null; });
			}
			finally {
				frame.release();
			}
		}
		assertTrue(storage.size() <= 3);
		for (ByteBuffer data : storage) {
			assertTrue(data.isDirect());
			assertEquals(64 * 48 * 4, data.capacity());
		}
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
	void anyRotationMultiple() throws Exception {
		// A rotation outside 0..270 is shown as its equal inside it.
		assertSamePixels(rotated(270), rotated(-90));
		assertSamePixels(rotated(90), rotated(450));

		// Sideways, a 4:3 picture in a 4:3 view leaves bars on the sides.
		VideoView view = sized(64, 48);
		view.accept(frame(64, 48, 128, 128, -90));
		presented(view);

		Image painted = paint(view);
		assertEquals(Color.BLACK, painted.getPixelReader().getColor(2, 24));
		assertGray(painted.getPixelReader().getColor(32, 24));
	}

	@Test
	void showsRemoteVideo() throws Exception {
		SignalingPair link = Signaling.loopback();
		Peer caller = rtc.createPeer(PeerOptions.builder().signaling(link.first()).build());
		Peer callee = rtc.createPeer(PeerOptions.builder().signaling(link.second()).polite(true).build());
		CustomVideo video = rtc.media().customVideo("video");
		VideoView view = onFx(() -> new VideoView());

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

	/** What a 64x48 view paints of a 64x48 two-tone frame with the given rotation. */
	private Image rotated(int rotation) throws Exception {
		VideoView view = sized(64, 48);
		view.accept(frame(64, 48, 16, 235, rotation));
		presented(view);

		return paint(view);
	}

	private static VideoFrame frame(int width, int height, int left, int right) {
		return frame(width, height, left, right, 0);
	}

	/**
	 * A frame whose left half has luma {@code left} and right half {@code right}, with
	 * neutral chroma.
	 */
	private static VideoFrame frame(int width, int height, int left, int right, int rotation) {
		NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
		ByteBuffer y = buffer.getDataY();

		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				y.put(row * buffer.getStrideY() + column, (byte) (column < width / 2 ? left : right));
			}
		}

		fill(buffer.getDataU());
		fill(buffer.getDataV());

		return new VideoFrame(buffer, rotation, System.nanoTime());
	}

	private static void fill(ByteBuffer plane) {
		for (int i = 0; i < plane.capacity(); i++) {
			plane.put(i, (byte) 128);
		}
	}

	private static void assertSamePixels(Image expected, Image actual) {
		assertEquals((int) expected.getWidth(), (int) actual.getWidth());
		assertEquals((int) expected.getHeight(), (int) actual.getHeight());

		for (int y = 0; y < (int) expected.getHeight(); y++) {
			for (int x = 0; x < (int) expected.getWidth(); x++) {
				assertEquals(expected.getPixelReader().getArgb(x, y), actual.getPixelReader().getArgb(x, y),
						"Pixel " + x + "," + y);
			}
		}
	}

	private static void assertGray(Color color) {
		assertNotNull(color);
		assertTrue(Math.abs(color.getRed() - color.getGreen()) < 0.04 && Math.abs(color.getGreen() - color.getBlue()) < 0.04
				&& color.getGreen() > 0.4 && color.getGreen() < 0.63, () -> "Not mid gray: " + color);
	}

}
