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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.media.video.VideoDevice;

import org.junit.jupiter.api.Test;

/**
 * Captures real cameras, so it skips on machines without one, as CI runners
 * are.
 */
class CameraIntegrationTest extends ClientTestBase {

	@Test
	void cameraReachesPeer() throws Exception {
		List<VideoDevice> cameras = cameras(1);
		Peer[] peers = pair();
		LocalCamera camera = rtc.media().camera(CameraOptions.builder()
				.device(cameras.get(0))
				.resolution(640, 480)
				.frameRate(30)
				.build());
		CompletableFuture<Integer> remote = new CompletableFuture<>();
		CompletableFuture<Integer> preview = new CompletableFuture<>();

		peers[1].onTrack(track -> track.asVideo().onFrame(frame -> remote.complete(frame.buffer.getWidth())));
		camera.onFrame(frame -> preview.complete(frame.buffer.getWidth()));
		peers[0].addTrack(camera);

		assertEquals(cameras.get(0), camera.device());
		assertEquals(640, (int) await(preview));
		assertEquals(640, (int) await(remote));
	}

	@Test
	void sharedCapture() throws Exception {
		VideoDevice device = cameras(1).get(0);
		CameraOptions options = CameraOptions.builder().device(device).resolution(640, 480).build();

		LocalCamera first = rtc.media().camera(options);
		LocalCamera second = rtc.media().camera(options);

		// One device, one set of options at a time.
		assertThrows(MediaException.class, () -> rtc.media().camera(CameraOptions.builder()
				.device(device).resolution(320, 240).build()));

		first.close();

		// The capture stays open for the track still using it.
		CompletableFuture<Integer> frame = new CompletableFuture<>();
		second.onFrame(video -> frame.complete(video.buffer.getWidth()));
		assertEquals(640, (int) await(frame));
	}

	@Test
	void switchesCamera() throws Exception {
		List<VideoDevice> cameras = cameras(2);
		Peer[] one = pair();
		Peer[] two = pair();
		LocalCamera camera = rtc.media().camera(CameraOptions.builder()
				.device(cameras.get(0))
				.resolution(1920, 1080)
				.frameRate(30)
				.build());
		// Senders scale the frames to what their encoders want, but keep the
		// aspect ratio, which tells the cameras apart.
		AtomicReference<Double> previewAspect = new AtomicReference<>(0.0);
		AtomicInteger atOne = new AtomicInteger();
		AtomicInteger atTwo = new AtomicInteger();

		camera.onFrame(frame -> previewAspect.set((double) frame.buffer.getWidth() / frame.buffer.getHeight()));
		one[1].onTrack(track -> track.asVideo().onFrame(frame -> atOne.incrementAndGet()));
		two[1].onTrack(track -> track.asVideo().onFrame(frame -> atTwo.incrementAndGet()));
		Sender senderOne = one[0].addTrack(camera);
		two[0].addTrack(camera);

		waitFor(() -> atOne.get() > 5 && atTwo.get() > 5, "frames before the switch");
		double aspectBefore = previewAspect.get();

		await(camera.switchCamera(cameras.get(1)));

		int oneBefore = atOne.get();
		int twoBefore = atTwo.get();

		// Both peers keep receiving, and the preview shows the other camera.
		waitFor(() -> atOne.get() > oneBefore + 10 && atTwo.get() > twoBefore + 10, "frames after the switch");
		waitFor(() -> Math.abs(previewAspect.get() - aspectBefore) > 0.1, "the other camera's frames in the preview");
		assertEquals(cameras.get(1), camera.device());
		assertEquals(camera, senderOne.track());

		// Back to the first, which the switch let go of.
		await(camera.switchCamera(cameras.get(0)));
		waitFor(() -> Math.abs(previewAspect.get() - aspectBefore) < 0.05, "the first camera's frames again");

		// A camera that cannot be opened fails the switch, and the track keeps its camera.
		VideoDevice missing = new VideoDevice("Missing Camera", "/dev/video-missing") { };
		ExecutionException failed = assertThrows(ExecutionException.class,
				() -> camera.switchCamera(missing).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertInstanceOf(MediaException.class, failed.getCause());
		assertEquals(cameras.get(0), camera.device());

		int oneAfter = atOne.get();
		waitFor(() -> atOne.get() > oneAfter + 10, "frames after the failed switch");
	}

	@Test
	void latePeerFailsSwitch() throws Exception {
		List<VideoDevice> cameras = cameras(2);

		rtc.close();
		rtc = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add)
				.operationTimeout(Duration.ofSeconds(2))
				.build();

		Peer[] peers = pair();
		LocalCamera camera = rtc.media().camera(CameraOptions.builder().device(cameras.get(0)).build());
		AtomicInteger received = new AtomicInteger();

		// Some cameras cannot capture at the same time. Then the switch lets
		// go of the old one first, and cannot go back to it.
		boolean together;

		try {
			await(rtc.media().camera(CameraOptions.builder().device(cameras.get(1)).build()).closeAsync());
			together = true;
		}
		catch (MediaException e) {
			together = false;
		}

		peers[1].onTrack(track -> track.asVideo().onFrame(frame -> received.incrementAndGet()));
		peers[0].addTrack(camera);
		waitFor(() -> received.get() > 5, "frames before the switch");

		// The peer's control executor is busy, so the peer takes neither the
		// new camera, nor the old one back, in time.
		CountDownLatch busy = new CountDownLatch(1);
		((PeerImpl) peers[0]).control.execute(() -> {
			try {
				busy.await();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});

		ExecutionException failed = assertThrows(ExecutionException.class,
				() -> camera.switchCamera(cameras.get(1)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertInstanceOf(WebRtcException.class, failed.getCause());
		assertInstanceOf(TimeoutException.class, failed.getCause().getCause());
		assertFalse(camera.isEnded());

		if (together) {
			assertEquals(cameras.get(0), camera.device());
			assertEquals(1, errors.size(), () -> "Errors: " + errors);
			assertTrue(errors.remove(0).getMessage().startsWith("Not every peer went back"));
		}
		else {
			assertEquals(cameras.get(1), camera.device());
		}

		// Once free, the peer sends the camera the track has. The track it
		// sent before is released then, which the runtime's close checks.
		busy.countDown();
		int before = received.get();
		waitFor(() -> received.get() > before + 10, "frames after the failed switch");
	}

	@Test
	void lostCameraEndsTrack() throws Exception {
		VideoDevice device = cameras(1).get(0);
		CameraOptions first = CameraOptions.builder().device(device).resolution(640, 480).build();
		CameraOptions second = CameraOptions.builder().device(device).resolution(320, 240).build();
		AtomicReference<LocalCamera> other = new AtomicReference<>();

		LocalCamera camera = rtc.media().camera(first);

		// The switch lets go of the camera as the missing one cannot be opened
		// beside it. Meanwhile another track takes the camera with other
		// options, so the switch cannot open it again.
		VideoDevice missing = new VideoDevice("Missing Camera", "/dev/video-missing") {

			@Override
			public String getDescriptor() {
				if (other.get() == null) {
					try {
						other.set(rtc.media().camera(second));
					}
					catch (MediaException stillOpen) {
						// Not let go of yet.
					}
				}

				return super.getDescriptor();
			}
		};

		ExecutionException failed = assertThrows(ExecutionException.class,
				() -> camera.switchCamera(missing).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		assertInstanceOf(MediaException.class, failed.getCause());
		assertTrue(camera.isEnded());
		assertNotNull(other.get());

		// Closing the ended track lets go of nothing the other track uses.
		await(camera.closeAsync());

		CompletableFuture<Integer> frame = new CompletableFuture<>();
		other.get().onFrame(video -> frame.complete(video.buffer.getWidth()));
		assertEquals(320, (int) await(frame));

		// The capture of the other track is still the one shared.
		assertThrows(MediaException.class, () -> rtc.media().camera(first));
	}

	private List<VideoDevice> cameras(int needed) {
		List<VideoDevice> cameras = rtc.media().devices().cameras();
		assumeTrue(cameras.size() >= needed, "Needs " + needed + " camera(s), found " + cameras.size());
		return cameras;
	}

	private static void waitFor(java.util.function.BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Timed out waiting for " + what);
			}
			Thread.sleep(50);
		}
	}

}
