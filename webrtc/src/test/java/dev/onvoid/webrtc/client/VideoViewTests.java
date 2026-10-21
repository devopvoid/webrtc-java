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

package dev.onvoid.webrtc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.client.swing.VideoView;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.junit.jupiter.api.Test;

class VideoViewTests extends ClientTestBase {

	@Test
	void showsFrame() {
		VideoView view = new VideoView();
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
	void keepsAspectRatio() {
		VideoView view = new VideoView();
		view.setSize(200, 100);
		view.accept(frame(64, 48, 128, 128));

		// A 4:3 picture in a 2:1 view leaves bars on the sides.
		BufferedImage painted = paint(view);
		assertEquals(Color.BLACK, new Color(painted.getRGB(5, 50)));
		assertGray(new Color(painted.getRGB(100, 50)));
	}

	@Test
	void mirrors() {
		VideoView view = new VideoView();
		view.setSize(64, 48);
		view.accept(frame(64, 48, 16, 235));

		assertTrue(brightness(paint(view).getRGB(8, 24)) < 64);

		view.setMirrored(true);

		// The bright right half is now on the left.
		assertTrue(brightness(paint(view).getRGB(8, 24)) > 192);
	}

	@Test
	void showsRemoteVideo() throws Exception {
		Peer[] peers = pair();
		CustomVideo video = rtc.media().customVideo("video");
		VideoView view = new VideoView();

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
