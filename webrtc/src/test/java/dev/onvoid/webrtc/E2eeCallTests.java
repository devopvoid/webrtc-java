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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import dev.onvoid.webrtc.e2ee.CryptoFailure;
import dev.onvoid.webrtc.e2ee.KeyProvider;
import dev.onvoid.webrtc.internal.AllocationFault;
import dev.onvoid.webrtc.internal.NativeApi;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests end-to-end encryption with a {@link KeyProvider} on a call between
 * two local peer connections, through WebRTC's real packetizers, depacketizers
 * and decoders.
 */
@Execution(ExecutionMode.SAME_THREAD)
class E2eeCallTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 10;

	private static final long CALLER = 1;
	private static final long CALLEE = 2;

	private KeyProvider callerKeys;
	private KeyProvider calleeKeys;

	private byte[] callerKey;


	@BeforeEach
	void createKeys() {
		callerKeys = KeyProvider.builder(CALLER).build();
		calleeKeys = KeyProvider.builder(CALLEE).build();

		callerKey = callerKeys.generateKey();
		callerKeys.setEncryptionKey(0, callerKey);
		calleeKeys.setDecryptionKey(CALLER, 0, callerKey);
	}

	@Test
	void encryptedVp8Decodes() throws Exception {
		assertVideoDecodes("VP8");
	}

	@Test
	void encryptedVp9Decodes() throws Exception {
		assertVideoDecodes("VP9");
	}

	@Test
	void encryptedAv1Decodes() throws Exception {
		assertVideoDecodes("AV1");
	}

	private void assertVideoDecodes(String codec) throws Exception {
		assumeTrue(canSend(codec), codec + " is not available");

		AtomicInteger decoded = new AtomicInteger();

		try (TestMediaCall call = new TestMediaCall(factory, true, false, codec)) {
			RTCRtpSender sender = call.getVideoSender();
			sender.setTransform(callerKeys.createEncryptor(sender::generateKeyFrame));

			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			receiver.setTransform(calleeKeys.createDecryptor(receiver::requestKeyFrame));

			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				frame.release();
				decoded.incrementAndGet();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();

			awaitTrue(() -> decoded.get() >= 10, () -> codec + ": too few frames decoded, "
					+ callerKeys.getStats() + ", " + calleeKeys.getStats());

			String sent = call.getSentVideoCodec();
			assertTrue(sent == null || sent.equalsIgnoreCase("video/" + codec), sent);

			// Nothing was dropped on the way: every frame authenticated.
			assertEquals(0, callerKeys.getStats().getDroppedFrames());
			assertEquals(0, calleeKeys.getStats().getDroppedFrames());

			track.removeSink(sink);
			receiver.dispose();
		}
	}

	@Test
	void encryptedAudioDecrypts() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, false, true)) {
			call.getAudioSender().setTransform(callerKeys.createEncryptor());

			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("audio");
			receiver.setTransform(calleeKeys.createDecryptor());

			call.awaitConnected();
			call.startMedia();

			awaitTrue(() -> calleeKeys.getStats().getDecryptedFrames() >= 20,
					() -> "too few audio frames decrypted: " + calleeKeys.getStats());

			assertEquals(0, calleeKeys.getStats().getDroppedFrames());

			receiver.dispose();
		}
	}

	@Test
	void missingKeyRecovers() throws Exception {
		KeyProvider keys = KeyProvider.builder(CALLEE).build();
		AtomicInteger decoded = new AtomicInteger();

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			RTCRtpSender sender = call.getVideoSender();
			sender.setTransform(callerKeys.createEncryptor(sender::generateKeyFrame));

			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			receiver.setTransform(keys.createDecryptor(receiver::requestKeyFrame));

			VideoTrack track = (VideoTrack) receiver.getTrack();
			VideoTrackSink sink = frame -> {
				frame.release();
				decoded.incrementAndGet();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();

			// Without the key, every frame is dropped before the decoder.
			awaitTrue(() -> keys.getStats().getDroppedFrames(CryptoFailure.UNKNOWN_KEY) >= 30,
					() -> "too few frames dropped: " + keys.getStats());
			assertEquals(0, decoded.get());
			assertEquals(0, keys.getStats().getDecryptedFrames());

			// The key arrives late: the decryptor asks for a key frame, and
			// the picture comes back without waiting for the next one.
			keys.setDecryptionKey(CALLER, 0, callerKey);

			awaitTrue(() -> decoded.get() >= 5, () -> "no frames decoded after the key arrived: "
					+ keys.getStats());

			track.removeSink(sink);
			receiver.dispose();
		}
	}

	@Test
	void rotationKeepsDecoding() throws Exception {
		AtomicInteger decoded = new AtomicInteger();

		try (TestMediaCall call = new TestMediaCall(factory, true, true)) {
			RTCRtpSender video = call.getVideoSender();
			video.setTransform(callerKeys.createEncryptor(video::generateKeyFrame));
			call.getAudioSender().setTransform(callerKeys.createEncryptor());

			call.negotiate();

			RTCRtpReceiver videoReceiver = call.getReceiver("video");
			RTCRtpReceiver audioReceiver = call.getReceiver("audio");
			videoReceiver.setTransform(calleeKeys.createDecryptor(videoReceiver::requestKeyFrame));
			audioReceiver.setTransform(calleeKeys.createDecryptor());

			VideoTrack track = (VideoTrack) videoReceiver.getTrack();
			VideoTrackSink sink = frame -> {
				frame.release();
				decoded.incrementAndGet();
			};
			track.addSink(sink);

			call.awaitConnected();
			call.startMedia();

			awaitTrue(() -> decoded.get() >= 10, () -> "too few frames decoded");

			// The new key reaches the callee first, then the caller uses it.
			byte[] next = callerKeys.generateKey();
			calleeKeys.setDecryptionKey(CALLER, 1, next);
			callerKeys.setEncryptionKey(1, next);

			int before = decoded.get();
			long decryptedBefore = calleeKeys.getStats().getDecryptedFrames();

			awaitTrue(() -> decoded.get() >= before + 20
					&& calleeKeys.getStats().getDecryptedFrames() >= decryptedBefore + 40,
					() -> "decoding stopped after the key changed: " + calleeKeys.getStats());

			assertEquals(0, calleeKeys.getStats().getDroppedFrames());

			// The caller removes the old key everywhere: frames keep coming.
			assertTrue(calleeKeys.removeDecryptionKey(CALLER, 0));
			int after = decoded.get();

			awaitTrue(() -> decoded.get() >= after + 10, () -> "decoding stopped without the old key");

			track.removeSink(sink);
			videoReceiver.dispose();
			audioReceiver.dispose();
		}
	}

	@Test
	void sealedTransformHidesFrames() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			RTCRtpSender sender = call.getVideoSender();
			long senderFrames = NativeApi.encodedFramesOf(sender);
			long sent = AllocationFault.addCountingObserver(senderFrames);

			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			long receiverFrames = NativeApi.encodedFramesOf(receiver);
			long received = AllocationFault.addCountingObserver(receiverFrames);

			try {
				// A transform of its own does not hide anything.
				sender.setTransform(frame -> { });
				assertFalse(NativeApi.isSealed(senderFrames));

				call.awaitConnected();
				call.startMedia();

				awaitTrue(() -> AllocationFault.countedFrames(sent) >= 10
						&& AllocationFault.countedFrames(received) >= 10, () -> "observers saw no frames");

				// The encryptor and decryptor of the call keep the frames
				// from the observers, from the moment they are set.
				sender.setTransform(callerKeys.createEncryptor());
				receiver.setTransform(calleeKeys.createDecryptor());

				assertTrue(NativeApi.isSealed(senderFrames));
				assertTrue(NativeApi.isSealed(receiverFrames));

				long decryptedBefore = calleeKeys.getStats().getDecryptedFrames();
				awaitTrue(() -> calleeKeys.getStats().getDecryptedFrames() >= decryptedBefore + 2,
						() -> "nothing decrypted");

				long sentSealed = AllocationFault.countedFrames(sent);
				long receivedSealed = AllocationFault.countedFrames(received);

				awaitTrue(() -> calleeKeys.getStats().getDecryptedFrames() >= decryptedBefore + 30,
						() -> "too few frames decrypted");

				assertEquals(sentSealed, AllocationFault.countedFrames(sent));
				assertEquals(receivedSealed, AllocationFault.countedFrames(received));

				// Removing the transform shows the frames again.
				sender.setTransform(null);
				receiver.setTransform(null);

				assertFalse(NativeApi.isSealed(senderFrames));
				assertFalse(NativeApi.isSealed(receiverFrames));

				awaitTrue(() -> AllocationFault.countedFrames(sent) > sentSealed + 10
						&& AllocationFault.countedFrames(received) > receivedSealed + 10,
						() -> "observers saw no frames after the transform was removed");
			}
			finally {
				AllocationFault.removeCountingObserver(sent);
				AllocationFault.removeCountingObserver(received);
				NativeApi.releaseEncodedFrames(senderFrames);
				NativeApi.releaseEncodedFrames(receiverFrames);
				receiver.dispose();
			}
		}
	}

	@Test
	void allowedRecordingShowsFrames() throws Exception {
		KeyProvider open = KeyProvider.builder(CALLER).allowPlaintextRecording(true).build();
		open.setEncryptionKey(0, callerKey);

		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			RTCRtpSender sender = call.getVideoSender();
			sender.setTransform(open.createEncryptor());

			long frames = NativeApi.encodedFramesOf(sender);
			long sent = AllocationFault.addCountingObserver(frames);

			try {
				assertFalse(NativeApi.isSealed(frames));

				call.negotiate();
				call.awaitConnected();
				call.startMedia();

				awaitTrue(() -> AllocationFault.countedFrames(sent) >= 10, () -> "the observer saw no frames");
			}
			finally {
				AllocationFault.removeCountingObserver(sent);
				NativeApi.releaseEncodedFrames(frames);
			}
		}
	}

	private boolean canSend(String codec) {
		RTCRtpCapabilities capabilities = factory.getRtpSenderCapabilities(MediaType.VIDEO);

		return capabilities.getCodecs().stream().anyMatch(c -> codec.equalsIgnoreCase(c.getName()));
	}

	private static void awaitTrue(BooleanSupplier condition, java.util.function.Supplier<String> message)
			throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				fail(message.get());
			}

			Thread.sleep(20);
		}
	}

}
