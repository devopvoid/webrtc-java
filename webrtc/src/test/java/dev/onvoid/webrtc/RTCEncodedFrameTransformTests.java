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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests encoded frame transforms on a call between two local peer
 * connections.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCEncodedFrameTransformTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;


	@Test
	void encryptedVideoRoundTrips() throws Exception {
		AtomicInteger encrypted = new AtomicInteger();
		AtomicInteger decrypted = new AtomicInteger();
		AtomicReference<String> mimeType = new AtomicReference<>();
		CountDownLatch decoded = new CountDownLatch(10);

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.getVideoSender().setTransform(frame -> {
				mimeType.compareAndSet(null, frame.getMimeType());
				xor(frame);
				encrypted.incrementAndGet();
			});

			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			receiver.setTransform(frame -> {
				xor(frame);
				decrypted.incrementAndGet();
			});

			// Frames only decode if the receiver undid what the sender did.
			// The remote track belongs to the receiver and is not disposed.
			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				frame.release();
				decoded.countDown();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();

			assertTrue(decoded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
					"too few frames decoded: encrypted " + encrypted.get()
							+ ", decrypted " + decrypted.get());

			assertTrue(mimeType.get().startsWith("video/"), mimeType.get());
			assertTrue(decrypted.get() > 0);

			track.removeSink(sink);
			receiver.dispose();
		}
	}

	@Test
	void videoFramesCarryMetadata() throws Exception {
		AtomicReference<RTCEncodedVideoFrame> first = new AtomicReference<>();
		AtomicReference<Integer> firstSize = new AtomicReference<>();
		CountDownLatch seen = new CountDownLatch(1);

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.getVideoSender().setTransform(frame -> {
				if (first.compareAndSet(null, (RTCEncodedVideoFrame) frame)) {
					firstSize.set(frame.getData().remaining());
					seen.countDown();
				}
			});

			call.negotiate();
			call.awaitConnected();
			call.startMedia();

			assertTrue(seen.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			RTCEncodedVideoFrame frame = first.get();

			// A stream starts with a key frame, which says how large it is.
			assertTrue(frame.isKeyFrame());
			assertEquals(320, frame.getWidth());
			assertEquals(240, frame.getHeight());
			assertEquals(frame.getSize(), (int) firstSize.get());
			assertTrue(frame.getSize() > 0);
			assertTrue(frame.getSsrc() > 0);
			assertTrue(frame.getPayloadType() > 0);
		}
	}

	@Test
	void frameIsInvalidAfterTransform() throws Exception {
		AtomicReference<RTCEncodedFrame> kept = new AtomicReference<>();
		CountDownLatch seen = new CountDownLatch(1);

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.getVideoSender().setTransform(frame -> {
				if (kept.compareAndSet(null, frame)) {
					seen.countDown();
				}
			});

			call.negotiate();
			call.awaitConnected();
			call.startMedia();

			assertTrue(seen.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			RTCEncodedFrame frame = kept.get();

			// Waits for the transform to have returned from this frame.
			Thread.sleep(200);

			assertThrows(IllegalStateException.class, frame::getData);
			assertThrows(IllegalStateException.class, () -> frame.setData(new byte[1]));
			assertThrows(IllegalStateException.class, frame::drop);

			// The metadata is a copy, and stays.
			assertNotNull(frame.getMimeType());
		}
	}

	@Test
	void droppedFramesNeverArrive() throws Exception {
		AtomicInteger dropped = new AtomicInteger();
		AtomicInteger received = new AtomicInteger();

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.getVideoSender().setTransform(frame -> {
				frame.drop();
				dropped.incrementAndGet();
			});

			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			receiver.setTransform(frame -> received.incrementAndGet());

			call.awaitConnected();
			call.startMedia();

			waitFor(() -> dropped.get() >= 30);
			Thread.sleep(500);

			assertEquals(0, received.get());

			// Without a transform, frames flow again.
			call.getVideoSender().setTransform(null);

			waitFor(() -> received.get() > 0);

			receiver.dispose();
		}
	}

	@Test
	void throwingTransformDropsFrames() throws Exception {
		AtomicInteger thrown = new AtomicInteger();
		AtomicInteger received = new AtomicInteger();
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();

		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
			if (e instanceof IllegalArgumentException) {
				thrown.incrementAndGet();
			}
		});

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.getVideoSender().setTransform(frame -> {
				throw new IllegalArgumentException("test");
			});

			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			receiver.setTransform(frame -> received.incrementAndGet());

			call.awaitConnected();
			call.startMedia();

			waitFor(() -> thrown.get() >= 30);

			assertEquals(0, received.get());

			receiver.dispose();
		}
		finally {
			Thread.setDefaultUncaughtExceptionHandler(previous);
		}
	}

	@Test
	void audioFramesAreTransformed() throws Exception {
		AtomicReference<RTCEncodedFrame> first = new AtomicReference<>();
		AtomicInteger received = new AtomicInteger();

		try (TestMediaCall call = new TestMediaCall(factory, false, true)) {
			call.getAudioSender().setTransform(frame -> {
				first.compareAndSet(null, frame);
				xor(frame);
			});

			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("audio");
			receiver.setTransform(frame -> {
				xor(frame);
				received.incrementAndGet();
			});

			call.awaitConnected();
			call.startMedia();

			waitFor(() -> received.get() >= 50);

			assertTrue(first.get() instanceof RTCEncodedAudioFrame);
			assertEquals("audio/opus", first.get().getMimeType().toLowerCase());

			receiver.dispose();
		}
	}

	@Test
	void requestKeyFrameReachesSender() throws Exception {
		AtomicInteger keyFrames = new AtomicInteger();
		AtomicInteger frames = new AtomicInteger();

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.getVideoSender().setTransform(frame -> {
				frames.incrementAndGet();

				if (((RTCEncodedVideoFrame) frame).isKeyFrame()) {
					keyFrames.incrementAndGet();
				}
			});

			call.negotiate();
			call.awaitConnected();
			call.startMedia();

			waitFor(() -> frames.get() >= 30);

			int before = keyFrames.get();

			RTCRtpReceiver receiver = call.getReceiver("video");
			receiver.requestKeyFrame();

			waitFor(() -> keyFrames.get() > before);

			int afterRequest = keyFrames.get();

			call.getVideoSender().generateKeyFrame();

			waitFor(() -> keyFrames.get() > afterRequest);

			receiver.dispose();
		}
	}

	@Test
	void clearingUnsetTransformDoesNothing() {
		try (TestMediaCall call = new TestMediaCall(factory, true, true)) {
			call.getVideoSender().setTransform(null);
			call.getAudioSender().setTransform(null);

			// Audio has no key frames; asking for one is not an error.
			call.getAudioSender().generateKeyFrame();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void transformIsSharedBetweenInstances() throws Exception {
		AtomicInteger first = new AtomicInteger();
		AtomicInteger second = new AtomicInteger();

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			RTCRtpSender videoSender = call.getVideoSender();

			call.getVideoSender().setTransform(frame -> first.incrementAndGet());

			call.negotiate();
			call.awaitConnected();
			call.startMedia();

			waitFor(() -> first.get() > 0);

			// Another instance for the same native sender replaces the
			// transform, rather than installing a second one.
			for (RTCRtpSender sender : call.getCallerSenders()) {
				if (sender.equals(videoSender)) {
					sender.setTransform(frame -> second.incrementAndGet());
				}
				sender.dispose();
			}

			int firstCount = first.get();

			waitFor(() -> second.get() > 0);
			Thread.sleep(200);

			assertTrue(first.get() - firstCount < 10, "the old transform still runs");
		}
	}

	private static void xor(RTCEncodedFrame frame) {
		ByteBuffer data = frame.getData();

		for (int i = 0; i < data.limit(); i++) {
			data.put(i, (byte) (data.get(i) ^ 0x5A));
		}

		frame.setData(data);
	}

	private static void waitFor(Condition condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (!condition.met()) {
			if (System.nanoTime() > deadline) {
				fail("timed out waiting");
			}

			Thread.sleep(20);
		}
	}

	@FunctionalInterface
	private interface Condition {

		boolean met();
	}

}
