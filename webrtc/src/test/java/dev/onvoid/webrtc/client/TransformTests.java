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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import dev.onvoid.webrtc.RTCEncodedFrame;
import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Transforms the encoded frames of senders and remote tracks: the transforms
 * of the peer options are in place from the first frame, those of a sender or
 * track replace them, and a transform that throws drops its frames.
 */
class TransformTests extends ClientTestBase {

	/** What the send transforms append and the receive transforms strip. */
	private static final byte[] MARK = { 'w', 'r', 't', 'c' };

	private ScheduledExecutorService feeder;


	@BeforeEach
	void createFeeder() {
		feeder = Executors.newSingleThreadScheduledExecutor();
	}

	@AfterEach
	void stopFeeder() throws InterruptedException {
		feeder.shutdownNow();
		feeder.awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void peerTransformsEveryFrame() throws Exception {
		AtomicInteger marked = new AtomicInteger();
		AtomicInteger unmarked = new AtomicInteger();
		Peer[] peers = pair(options -> options
				.sendTransform(TransformTests::mark)
				.receiveTransform(frame -> (unmark(frame) ? marked : unmarked).incrementAndGet()));
		CustomVideo video = rtc.media().customVideo("video");
		CompletableFuture<Void> decoded = new CompletableFuture<>();

		peers[1].onTrack(track -> track.asVideo().onFrame(frame -> decoded.complete(null)));
		peers[0].addTrack(video);

		feedVideo(video);
		await(decoded);
		waitFor(() -> marked.get() >= 10);

		// Every frame decoded passed the receive transform first, so it was
		// in place before the first one arrived. The count of the decoder is
		// read first, as the transform runs ahead of it.
		long decodedFrames = framesDecoded(peers[1]);
		int transformed = marked.get();

		assertTrue(decodedFrames > 0 && decodedFrames <= transformed,
				() -> "Decoded " + decodedFrames + " frames, of which " + transformed + " were transformed");
		// And every frame sent passed the send transform.
		assertEquals(0, unmarked.get());
	}

	@Test
	void senderAndTrackTransformsReplacePeers() throws Exception {
		AtomicInteger byPeer = new AtomicInteger();
		AtomicInteger byTrack = new AtomicInteger();
		Peer[] peers = pair(options -> options
				.sendTransform(TransformTests::mark)
				.receiveTransform(frame -> {
					unmark(frame);
					byPeer.incrementAndGet();
				}));
		CustomVideo video = rtc.media().customVideo("video");
		CompletableFuture<RemoteTrack> received = new CompletableFuture<>();

		peers[1].onTrack(received::complete);
		Sender sender = peers[0].addTrack(video);

		feedVideo(video);
		RemoteTrack track = await(received);
		waitFor(() -> byPeer.get() > 0);

		// Frames sent unmarked from now on, and received as they are.
		await(sender.transform(null));
		await(track.transform(frame -> {
			if (!hasMark(frame)) {
				byTrack.incrementAndGet();
			}
		}));

		waitFor(() -> byTrack.get() >= 10);
	}

	@Test
	void trackTransformWithoutPeerTransform() throws Exception {
		Peer[] peers = pair();
		CustomVideo video = rtc.media().customVideo("video");
		AtomicInteger seen = new AtomicInteger();
		CompletableFuture<CompletableFuture<Void>> set = new CompletableFuture<>();

		// Set from the handler, as an application would.
		peers[1].onTrack(track -> set.complete(track.transform(frame -> seen.incrementAndGet())));
		Sender sender = peers[0].addTrack(video);
		await(sender.transform(frame -> { }));

		feedVideo(video);
		await(await(set));

		waitFor(() -> seen.get() >= 10);
	}

	@Test
	void throwingTransformDropsFrames() throws Exception {
		AtomicInteger thrown = new AtomicInteger();
		Peer[] peers = pair(options -> options.receiveTransform(frame -> {
			thrown.incrementAndGet();
			throw new IllegalStateException("No key");
		}));
		List<WebRtcException> failures = new CopyOnWriteArrayList<>();
		CustomVideo video = rtc.media().customVideo("video");

		peers[1].onError(failures::add);
		peers[0].addTrack(video);

		feedVideo(video);
		waitFor(() -> thrown.get() >= 10 && !failures.isEmpty());

		// Reported once, not for every frame.
		assertEquals(1, failures.size(), () -> "Failures: " + failures);
		assertInstanceOf(IllegalStateException.class, failures.get(0).getCause());
		// No frame got past the transform to the decoder.
		assertEquals(0, framesDecoded(peers[1]));
	}

	@Test
	void transformOfRemovedSenderFails() throws Exception {
		Peer[] peers = pair();
		Sender sender = peers[0].addTrack(rtc.media().customVideo("video"));

		await(sender.remove());

		assertFailure(IllegalStateException.class, sender.transform(frame -> { }));
	}

	@Test
	void transformOfEndedTrackFails() throws Exception {
		Peer[] peers = pair();
		CompletableFuture<RemoteTrack> received = new CompletableFuture<>();
		CompletableFuture<Void> ended = new CompletableFuture<>();

		peers[1].onTrack(track -> {
			received.complete(track);
			track.onEnded(() -> ended.complete(null));
		});
		Sender sender = peers[0].addTrack(rtc.media().customVideo("video"));
		RemoteTrack track = await(received);

		await(sender.remove());
		await(ended);

		assertFailure(IllegalStateException.class, track.transform(frame -> { }));
	}

	@Test
	void transformAfterPeerClosedFails() throws Exception {
		Peer[] peers = pair();
		CompletableFuture<RemoteTrack> received = new CompletableFuture<>();

		peers[1].onTrack(received::complete);
		Sender sender = peers[0].addTrack(rtc.media().customVideo("video"));
		RemoteTrack track = await(received);

		await(peers[0].closeAsync());
		await(peers[1].closeAsync());

		assertFailure(PeerClosedException.class, sender.transform(frame -> { }));
		assertFailure(PeerClosedException.class, track.transform(frame -> { }));
	}

	private static void mark(RTCEncodedFrame frame) {
		ByteBuffer data = frame.getData();
		ByteBuffer marked = ByteBuffer.allocate(data.remaining() + MARK.length);

		marked.put(data).put(MARK).flip();
		frame.setData(marked);
	}

	/**
	 * @return true if the frame was marked, which it no longer is.
	 */
	private static boolean unmark(RTCEncodedFrame frame) {
		if (!hasMark(frame)) {
			return false;
		}

		ByteBuffer data = frame.getData();
		data.limit(data.limit() - MARK.length);
		frame.setData(data);

		return true;
	}

	private static boolean hasMark(RTCEncodedFrame frame) {
		ByteBuffer data = frame.getData();
		int size = data.limit();

		if (size < MARK.length) {
			return false;
		}

		for (int i = 0; i < MARK.length; i++) {
			if (data.get(size - MARK.length + i) != MARK[i]) {
				return false;
			}
		}

		return true;
	}

	private void feedVideo(CustomVideo video) {
		AtomicInteger shade = new AtomicInteger();

		feeder.scheduleAtFixedRate(() -> {
			NativeI420Buffer buffer = NativeI420Buffer.allocate(160, 120);
			ByteBuffer y = buffer.getDataY();
			int offset = shade.addAndGet(3);

			for (int i = 0; i < y.capacity(); i++) {
				y.put(i, (byte) (i + offset));
			}

			VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
			video.push(frame);
			frame.release();
		}, 0, 33, TimeUnit.MILLISECONDS);
	}

	private static long framesDecoded(Peer peer) throws Exception {
		long frames = 0;

		for (RTCStats entry : await(peer.stats()).report().getStats().values()) {
			Object decoded = entry.getAttributes().get("framesDecoded");

			if (entry.getType() == RTCStatsType.INBOUND_RTP && decoded instanceof Number) {
				frames += ((Number) decoded).longValue();
			}
		}

		return frames;
	}

	/** Waits for frames to arrive, which only time brings. */
	private static void waitFor(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "Timed out");
			Thread.sleep(20);
		}
	}

	private static void assertFailure(Class<? extends Throwable> type, CompletableFuture<Void> future) {
		ExecutionException e = assertThrows(ExecutionException.class,
				() -> future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

		assertInstanceOf(type, e.getCause());
	}

}
