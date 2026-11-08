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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
import dev.onvoid.webrtc.e2ee.CryptoFailure;
import dev.onvoid.webrtc.e2ee.KeyProvider;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the end-to-end encryption of the peer options.
 */
class E2eeTests extends ClientTestBase {

	private static final long CALLER = 10;
	private static final long CALLEE = 20;

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
	void encryptedCallDecodes() throws Exception {
		KeyProvider callerKeys = KeyProvider.builder(CALLER).build();
		KeyProvider calleeKeys = KeyProvider.builder(CALLEE).build();
		exchangeKeys(callerKeys, calleeKeys);

		Peer[] peers = videoPair(callerKeys, calleeKeys);
		CustomVideo video = rtc.media().customVideo("video");
		AtomicInteger decoded = new AtomicInteger();

		peers[1].onTrack(track -> track.asVideo().onFrame(frame -> decoded.incrementAndGet()));
		peers[0].addTrack(video);

		feedVideo(video);
		waitFor(() -> decoded.get() >= 10);

		assertTrue(callerKeys.getStats().getEncryptedFrames() > 0);
		assertTrue(calleeKeys.getStats().getDecryptedFrames() >= 10);
		assertEquals(0, calleeKeys.getStats().getDroppedFrames());
	}

	@Test
	void lateKeyRecovers() throws Exception {
		KeyProvider callerKeys = KeyProvider.builder(CALLER).build();
		KeyProvider calleeKeys = KeyProvider.builder(CALLEE).build();
		byte[] key = callerKeys.generateKey();
		callerKeys.setEncryptionKey(3, key);

		Peer[] peers = videoPair(callerKeys, calleeKeys);
		CustomVideo video = rtc.media().customVideo("video");
		AtomicInteger decoded = new AtomicInteger();

		peers[1].onTrack(track -> track.asVideo().onFrame(frame -> decoded.incrementAndGet()));
		peers[0].addTrack(video);

		feedVideo(video);
		waitFor(() -> calleeKeys.getStats().getDroppedFrames(CryptoFailure.UNKNOWN_KEY) >= 30);
		assertEquals(0, decoded.get());

		// The remote track asks for a key frame through its peer once frames
		// decrypt again.
		calleeKeys.setDecryptionKey(CALLER, 3, key);

		waitFor(() -> decoded.get() >= 5);
	}

	@Test
	void e2eeTakesNoOtherTransform() {
		KeyProvider keys = KeyProvider.builder(CALLER).build();

		assertThrows(IllegalStateException.class, () -> PeerOptions.builder()
				.negotiation(Negotiation.MANUAL).e2ee(keys).sendTransform(frame -> { }).build());
		assertThrows(IllegalStateException.class, () -> PeerOptions.builder()
				.negotiation(Negotiation.MANUAL).e2ee(keys).receiveTransform(frame -> { }).build());
	}

	@Test
	void guardKeepsRecordingPolicy() {
		PeerImpl peer = (PeerImpl) rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
		KeyProvider sealed = KeyProvider.builder(CALLER).build();
		KeyProvider open = KeyProvider.builder(CALLER).allowPlaintextRecording(true).build();

		RTCEncodedFrameTransformer plain = frame -> { };

		assertFalse(peer.guard(sealed.createEncryptor(), "a sender").allowsPlaintextObservers());
		assertFalse(peer.guard(sealed.createDecryptor(), "a track").allowsPlaintextObservers());
		assertTrue(peer.guard(open.createEncryptor(), "a sender").allowsPlaintextObservers());
		assertTrue(peer.guard(plain, "a sender").allowsPlaintextObservers());
	}

	static void exchangeKeys(KeyProvider caller, KeyProvider callee) {
		byte[] callerKey = caller.generateKey();
		byte[] calleeKey = callee.generateKey();

		caller.setEncryptionKey(0, callerKey);
		callee.setEncryptionKey(0, calleeKey);
		caller.setDecryptionKey(callee.getParticipant(), 0, calleeKey);
		callee.setDecryptionKey(caller.getParticipant(), 0, callerKey);
	}

	private Peer[] videoPair(KeyProvider callerKeys, KeyProvider calleeKeys) throws Exception {
		SignalingPair link = Signaling.loopback();

		Peer caller = rtc.createPeer(PeerOptions.builder()
				.name("caller")
				.signaling(link.first())
				.e2ee(callerKeys)
				.build());
		Peer callee = rtc.createPeer(PeerOptions.builder()
				.name("callee")
				.signaling(link.second())
				.polite(true)
				.e2ee(calleeKeys)
				.build());

		Peer[] peers = { caller, callee };

		// A codec the frame format covers on every platform: macOS would
		// otherwise prefer H.264, which end-to-end encryption refuses.
		for (Peer peer : peers) {
			await(((PeerImpl) peer).manual.preferCodecs(Collections.singletonMap(
					MediaKind.VIDEO, Collections.singletonList("VP8"))));
		}

		return peers;
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

			fill(buffer.getDataU());
			fill(buffer.getDataV());

			VideoFrame frame = new VideoFrame(buffer, System.nanoTime());

			try {
				video.push(frame);
			}
			finally {
				frame.release();
			}
		}, 0, 33, TimeUnit.MILLISECONDS);
	}

	private static void fill(ByteBuffer plane) {
		while (plane.hasRemaining()) {
			plane.put((byte) 128);
		}
	}

	/** Waits for frames to arrive, which only time brings. */
	private static void waitFor(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, "Timed out");

			Thread.sleep(20);
		}
	}

}
