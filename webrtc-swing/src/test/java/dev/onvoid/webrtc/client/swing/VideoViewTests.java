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

package dev.onvoid.webrtc.client.swing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class VideoViewTests {

	private static final long TIMEOUT_SECONDS = 15;

	private final List<Throwable> errors = new CopyOnWriteArrayList<>();

	private WebRtc rtc;


	@BeforeEach
	void createRuntime() {
		// Also loads the native library the frames need.
		rtc = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.build();
	}

	@AfterEach
	void closeRuntime() {
		rtc.close();

		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	@Test
	void showsFrame() {
		VideoView view = new VideoView(320 * 240);
		assertNull(view.snapshot());

		view.accept(frame(64, 48, 128, 128));

		BufferedImage snapshot = view.snapshot();
		assertNotNull(snapshot);
		assertEquals(64, snapshot.getWidth());
		assertEquals(48, snapshot.getHeight());
		assertGray(new Color(snapshot.getRGB(32, 24)));

		view.clear();
		assertNull(view.snapshot());
	}

	@Test
	void conversionLeavesEdtFree() throws Exception {
		VideoView view = new VideoView(320 * 240);
		view.setSize(64, 48);
		VideoFrame initial = frame(64, 48, 128, 128);
		VideoFrame held = frame(64, 48, 16, 235);
		CountDownLatch converting = new CountDownLatch(1);
		CountDownLatch resume = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			view.accept(initial);
			VideoFrameBuffer blocked =
					(VideoFrameBuffer) Proxy.newProxyInstance(
							getClass().getClassLoader(),
							new Class<?>[] { VideoFrameBuffer.class },
							(proxy, method, args) -> {
								if (method.getName().equals("toI420")) {
									converting.countDown();
									assertTrue(resume.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
								}
								return method.invoke(held.buffer, args);
							});
			Future<?> delivery = executor.submit(() -> view.accept(new VideoFrame(blocked, 0)));
			assertTrue(converting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			FutureTask<Void> painting = new FutureTask<>(() -> {
				assertGray(new Color(paint(view).getRGB(32, 24)));
				view.clear();
				assertNull(view.snapshot());
				return null;
			});
			SwingUtilities.invokeLater(painting);
			painting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			resume.countDown();
			delivery.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			assertNull(view.snapshot());
			view.accept(initial);
			assertNotNull(view.snapshot());
		}
		finally {
			resume.countDown();
			executor.shutdownNow();
			executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			initial.release();
			held.release();
		}
	}

	@Test
	void rejectsInvalidSizes() throws Exception {
		assertThrows(IllegalArgumentException.class, () -> new VideoView(0));
		assertThrows(IllegalArgumentException.class,
				() -> new VideoView(VideoView.MAX_PIXELS + 1));
		VideoView view = new VideoView(64 * 48);
		for (int[] size : new int[][] { { 0, 1 }, { -1, -1 }, { 65, 48 },
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
	void reusesBuffersAcrossSizes() throws Exception {
		VideoView view = new VideoView(64 * 48);
		Set<ByteBuffer> storage = Collections.newSetFromMap(new IdentityHashMap<>());
		Field field = VideoView.class.getDeclaredField("pixels");
		field.setAccessible(true);
		for (int i = 0; i < 12; i++) {
			int width = i % 2 == 0 ? 64 : 32;
			VideoFrame frame = frame(width, 48, 128, 128);
			try {
				view.accept(frame);
				assertEquals(width, view.snapshot().getWidth());
				assertGray(new Color(view.snapshot().getRGB(0, 0)));
				storage.add((ByteBuffer) field.get(view));
				if (i >= 6) view.clear();
			}
			finally {
				frame.release();
			}
		}
		assertTrue(storage.size() <= 1);
		for (ByteBuffer data : storage) {
			assertTrue(data.isDirect());
			assertEquals(64 * 48 * 4, data.capacity());
		}
	}

	@Test
	void keepsAspectRatio() {
		VideoView view = new VideoView(320 * 240);
		view.setSize(200, 100);
		view.accept(frame(64, 48, 128, 128));

		// A 4:3 picture in a 2:1 view leaves bars on the sides.
		BufferedImage painted = paint(view);
		assertEquals(Color.BLACK, new Color(painted.getRGB(5, 50)));
		assertGray(new Color(painted.getRGB(100, 50)));
	}

	@Test
	void mirrors() {
		VideoView view = new VideoView(320 * 240);
		view.setSize(64, 48);
		view.accept(frame(64, 48, 16, 235));

		assertTrue(brightness(paint(view).getRGB(8, 24)) < 64);

		view.setMirrored(true);

		// The bright right half is now on the left.
		assertTrue(brightness(paint(view).getRGB(8, 24)) > 192);
	}

	@Test
	void anyRotationMultiple() {
		// A rotation outside 0..270 is shown as its equal inside it.
		assertArrayEquals(rotated(270), rotated(-90));
		assertArrayEquals(rotated(90), rotated(450));

		// Sideways, a 4:3 picture in a 4:3 view leaves bars on the sides.
		VideoView view = new VideoView(320 * 240);
		view.setSize(64, 48);
		view.accept(frame(64, 48, 128, 128, -90));

		BufferedImage painted = paint(view);
		assertEquals(Color.BLACK, new Color(painted.getRGB(2, 24)));
		assertGray(new Color(painted.getRGB(32, 24)));
	}

	@Test
	void showsRemoteVideo() throws Exception {
		Peer[] peers = pair();
		CustomVideo video = rtc.media().customVideo("video");
		VideoView view = new VideoView(320 * 240);

		peers[1].onTrack(track -> track.asVideo().onFrame(view));
		peers[0].addTrack(video);

		ScheduledExecutorService feeder = Executors.newSingleThreadScheduledExecutor();

		try {
			feeder.scheduleAtFixedRate(() -> {
				VideoFrame frame = frame(320, 240, 128, 128);
				video.push(frame);
				frame.release();
			}, 0, 33, TimeUnit.MILLISECONDS);

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
			while (view.snapshot() == null && System.nanoTime() < deadline) {
				Thread.sleep(50);
			}
		}
		finally {
			feeder.shutdownNow();
		}

		BufferedImage snapshot = view.snapshot();
		assertNotNull(snapshot, "No frame reached the view");
		assertEquals(320, snapshot.getWidth());
	}

	private Peer[] pair() {
		SignalingPair link = Signaling.loopback();

		Peer caller = rtc.createPeer(PeerOptions.builder()
				.name("caller")
				.signaling(link.first())
				.build());
		Peer callee = rtc.createPeer(PeerOptions.builder()
				.name("callee")
				.signaling(link.second())
				.polite(true)
				.build());

		return new Peer[] { caller, callee };
	}

	/** The pixels a 64x48 view paints of a 64x48 two-tone frame with the given rotation. */
	private static int[] rotated(int rotation) {
		VideoView view = new VideoView(320 * 240);
		view.setSize(64, 48);
		view.accept(frame(64, 48, 16, 235, rotation));

		return paint(view).getRGB(0, 0, 64, 48, null, 0, 64);
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

	private static BufferedImage paint(VideoView view) {
		BufferedImage image = new BufferedImage(view.getWidth(), view.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		view.paint(g);
		g.dispose();

		return image;
	}

	private static void assertGray(Color color) {
		assertTrue(Math.abs(color.getRed() - color.getGreen()) < 8 && Math.abs(color.getGreen() - color.getBlue()) < 8
				&& color.getGreen() > 100 && color.getGreen() < 160, () -> "Not mid gray: " + color);
	}

	private static int brightness(int rgb) {
		return new Color(rgb).getGreen();
	}

}
