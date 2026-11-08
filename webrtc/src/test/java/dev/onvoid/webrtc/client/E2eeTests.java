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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
import dev.onvoid.webrtc.e2ee.CryptoFailure;
import dev.onvoid.webrtc.e2ee.CryptoStats;
import dev.onvoid.webrtc.e2ee.KeyProvider;
import dev.onvoid.webrtc.media.MediaType;
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
		// Every frame dropped was a replay, if any: see E2eeCallTests.
		CryptoStats stats = calleeKeys.getStats();
		assertEquals(0, stats.getDroppedFrames() - stats.getDroppedFrames(CryptoFailure.REPLAY), stats::toString);
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
	void offersOnlyEncryptableVideo() throws Exception {
		Peer encrypted = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL)
				.e2ee(KeyProvider.builder(CALLER).build()).build());
		Peer plain = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());

		encrypted.addTrack(rtc.media().customVideo("encrypted"));
		encrypted.addTrack(rtc.media().customAudio("encrypted-audio"));
		plain.addTrack(rtc.media().customVideo("plain"));

		String encryptedOffer = await(encrypted.negotiator().createOffer()).sdp;
		String plainOffer = await(plain.negotiator().createOffer()).sdp;

		assertTrue(encryptedOffer.contains(" VP8/90000"), encryptedOffer);
		assertFalse(encryptedOffer.contains(" H264/90000"), encryptedOffer);
		assertFalse(encryptedOffer.contains(" H265/90000"), encryptedOffer);
		// Audio is left as it is.
		assertTrue(encryptedOffer.contains(" opus/48000"), encryptedOffer);

		// The plain peer offers what the encrypted one leaves out.
		assertTrue(plainOffer.contains(" VP8/90000"), plainOffer);

		if (hasH264()) {
			assertTrue(plainOffer.contains(" H264/90000"), plainOffer);
		}
	}

	@Test
	void unencryptableCodecsAreRefused() {
		PeerImpl peer = (PeerImpl) rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL)
				.e2ee(KeyProvider.builder(CALLER).build()).build());

		assumeTrue(hasH264(), "WebRTC has no H.264 here");

		ExecutionException e = assertThrows(ExecutionException.class, () -> await(peer.manual.preferCodecs(
				Collections.singletonMap(MediaKind.VIDEO, Collections.singletonList("H264")))));

		assertInstanceOf(ConfigurationException.class, e.getCause());
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

	private boolean hasH264() {
		return rtc.factory.getRtpSenderCapabilities(MediaType.VIDEO).getCodecs().stream()
				.anyMatch(codec -> "H264".equalsIgnoreCase(codec.getName()));
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

		// No codec preferences: the peers negotiate a codec end-to-end
		// encryption covers by themselves, also on macOS, which would
		// otherwise prefer H.264.
		return new Peer[] { caller, callee };
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
