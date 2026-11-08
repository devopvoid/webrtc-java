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

package dev.onvoid.webrtc.e2ee;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the encryptor and decryptor on frames given as bytes, the way a
 * sender's and a receiver's transform see them.
 */
class FrameCryptorTests {

	private static final long ALICE = 1;
	private static final long BOB = 2;

	private static final long SSRC = 1111;

	private final List<CryptoEvent> aliceEvents = new ArrayList<>();
	private final List<CryptoEvent> bobEvents = new ArrayList<>();

	private final Random random = new Random(42);

	private KeyProvider alice;
	private KeyProvider bob;

	private byte[] aliceKey;


	@BeforeEach
	void createKeys() {
		alice = KeyProvider.builder(ALICE).listener(aliceEvents::add).build();
		bob = KeyProvider.builder(BOB).listener(bobEvents::add).build();

		aliceKey = alice.generateKey();
		alice.setEncryptionKey(0, aliceKey);
		bob.setDecryptionKey(ALICE, 0, aliceKey);
	}

	@Test
	void everyCodecRoundTrips() {
		FrameCryptor encryptor = encryptor(alice);
		FrameCryptor decryptor = decryptor(bob);

		String[][] codecs = {
				{ "video/VP8", "key", "10" }, { "video/VP8", "delta", "3" },
				{ "video/VP9", "key", "0" }, { "video/VP9", "delta", "0" },
				{ "audio/opus", "audio", "1" }, { "audio/PCMU", "audio", "0" },
				{ "audio/red", "audio", "0" }
		};

		long ssrc = SSRC;

		for (String[] codec : codecs) {
			boolean video = !codec[1].equals("audio");
			boolean key = codec[1].equals("key");
			int clear = Integer.parseInt(codec[2]);
			byte[] frame = frame(500);

			byte[] sealed = process(encryptor, ssrc, codec[0], video, key, frame);

			assertNotNull(sealed, codec[0]);
			assertArrayEquals(Arrays.copyOf(frame, clear), Arrays.copyOf(sealed, clear),
					codec[0] + " must leave its header in the clear");
			assertFalse(Arrays.equals(Arrays.copyOfRange(frame, clear, clear + 16),
					Arrays.copyOfRange(sealed, clear, clear + 16)), codec[0]);

			assertArrayEquals(frame, process(decryptor, ssrc, codec[0], video, key, sealed), codec[0]);

			ssrc++;
		}

		assertTrue(bobEvents.isEmpty(), bobEvents.toString());
	}

	@Test
	void av1TravelsAsOneObu() {
		FrameCryptor encryptor = encryptor(alice);
		FrameCryptor decryptor = decryptor(bob);

		for (int size : new int[] { 10, 200, 20000 }) {
			byte[] frame = frame(size);
			byte[] sealed = process(encryptor, SSRC, "video/AV1", true, size == 10, frame);

			// OBU_SEQUENCE_HEADER for a key frame, so that the packetizer
			// marks it as one, OBU_FRAME otherwise; without extension and
			// size field.
			assertEquals(size == 10 ? 0x08 : 0x30, sealed[0] & 0xFF);

			// What the depacketizer hands over: the size bit set and a
			// leb128 size inserted.
			byte[] payload = Arrays.copyOfRange(sealed, 1, sealed.length);
			byte[] received = concat(new byte[] { (byte) (sealed[0] | 0x02) }, leb128(payload.length), payload);

			assertArrayEquals(frame, process(decryptor, SSRC, "video/AV1", true, size == 10, received));
			// And without the size field, as sent. A frame of its own, since
			// the first one is a replay now.
			byte[] next = frame(size);
			assertArrayEquals(next, process(decryptor, SSRC, "video/AV1", true, false,
					process(encryptor, SSRC, "video/AV1", true, false, next)));
		}

		// A size field that does not match is no frame of the format.
		byte[] sealed = process(encryptor, SSRC, "video/AV1", true, false, frame(100));
		byte[] payload = Arrays.copyOfRange(sealed, 1, sealed.length);
		byte[] wrong = concat(new byte[] { 0x32 }, leb128(payload.length + 1), payload);

		assertNull(process(decryptor, SSRC + 2, "video/AV1", true, false, wrong));
		assertEquals(CryptoFailure.MALFORMED, last(bobEvents).getFailure());

		// The wrapper is authenticated: a delta frame cannot pass for a key
		// frame.
		byte[] delta = process(encryptor, SSRC, "video/AV1", true, false, frame(100));
		delta[0] = 0x08;

		assertNull(process(decryptor, SSRC + 3, "video/AV1", true, true, delta));
		assertEquals(CryptoFailure.AUTHENTICATION, last(bobEvents).getFailure());
	}

	@Test
	void everySuiteRoundTrips() {
		for (CipherSuite suite : CipherSuite.values()) {
			KeyProvider sender = KeyProvider.builder(ALICE).cipherSuite(suite).build();
			KeyProvider receiver = KeyProvider.builder(BOB).cipherSuite(suite).build();
			byte[] key = sender.generateKey();

			assertEquals(suite.getHashLength(), key.length);

			sender.setEncryptionKey(7, key);
			receiver.setDecryptionKey(ALICE, 7, key);

			byte[] frame = frame(1000);
			byte[] sealed = process(encryptor(sender), SSRC, "video/VP8", true, true, frame);

			// The tag, and a header of a three-byte KID and a counter of zero.
			assertEquals(frame.length + 4 + suite.getTagLength(), sealed.length, suite.name());
			assertArrayEquals(frame, process(decryptor(receiver), SSRC, "video/VP8", true, true, sealed),
					suite.name());
		}
	}

	@Test
	void h264IsRefused() {
		FrameCryptor encryptor = encryptor(alice);

		assertNull(process(encryptor, SSRC, "video/H264", true, true, frame(100)));
		assertNull(process(encryptor, SSRC, "video/H264", true, false, frame(100)));
		assertNull(process(decryptor(bob), SSRC, "video/H265", true, false, frame(100)));

		// Reported once per stream, counted for every frame.
		assertEquals(1, aliceEvents.size());
		assertEquals(CryptoFailure.UNSUPPORTED_CODEC, aliceEvents.get(0).getFailure());
		assertTrue(aliceEvents.get(0).isOutgoing());
		assertEquals(2, alice.getStats().getDroppedFrames(CryptoFailure.UNSUPPORTED_CODEC));
	}

	@Test
	void missingKeyDropsInsteadOfSending() {
		KeyProvider keys = KeyProvider.builder(ALICE).listener(aliceEvents::add).build();
		AtomicInteger keyFrames = new AtomicInteger();
		FrameCryptor encryptor = (FrameCryptor) keys.createEncryptor(keyFrames::incrementAndGet);

		for (int i = 0; i < 5; i++) {
			assertNull(process(encryptor, SSRC, "video/VP8", true, i == 0, frame(100)));
		}

		assertEquals(1, aliceEvents.size());
		assertEquals(CryptoFailure.MISSING_KEY, aliceEvents.get(0).getFailure());
		assertEquals(-1, aliceEvents.get(0).getGeneration());
		assertEquals(ALICE, aliceEvents.get(0).getParticipant());

		keys.setEncryptionKey(0, aliceKey);

		// The first frame that goes out again asks for a key frame, since the
		// receivers missed the ones before.
		assertNotNull(process(encryptor, SSRC, "video/VP8", true, false, frame(100)));
		assertEquals(1, keyFrames.get());

		keys.removeEncryptionKey();
		assertNull(process(encryptor, SSRC, "video/VP8", true, false, frame(100)));
		assertEquals(2, aliceEvents.size());
	}

	@Test
	void unknownKeyDropsUntilItArrives() {
		KeyProvider carol = KeyProvider.builder(3).listener(bobEvents::add).build();
		AtomicInteger keyFrames = new AtomicInteger();
		FrameCryptor encryptor = encryptor(alice);
		FrameCryptor decryptor = (FrameCryptor) carol.createDecryptor(keyFrames::incrementAndGet);

		byte[][] frames = new byte[4][];

		for (int i = 0; i < frames.length; i++) {
			frames[i] = process(encryptor, SSRC, "video/VP8", true, i == 0, frame(300));
		}

		assertNull(process(decryptor, SSRC, "video/VP8", true, true, frames[0]));
		assertNull(process(decryptor, SSRC, "video/VP8", true, false, frames[1]));

		assertEquals(1, bobEvents.size());
		assertEquals(CryptoFailure.UNKNOWN_KEY, bobEvents.get(0).getFailure());
		assertEquals(ALICE, bobEvents.get(0).getParticipant());
		assertEquals(0, bobEvents.get(0).getGeneration());
		assertFalse(bobEvents.get(0).isOutgoing());

		carol.setDecryptionKey(ALICE, 0, aliceKey);

		// Decrypts again, and asks for a key frame for what was lost.
		assertNotNull(process(decryptor, SSRC, "video/VP8", true, false, frames[2]));
		assertEquals(1, keyFrames.get());

		// Not again right away, however many frames follow.
		assertNotNull(process(decryptor, SSRC, "video/VP8", true, false, frames[3]));
		assertEquals(1, keyFrames.get());

		CryptoStats stats = carol.getStats();
		assertEquals(2, stats.getDecryptedFrames());
		assertEquals(2, stats.getDroppedFrames(CryptoFailure.UNKNOWN_KEY));
	}

	@Test
	void replaysAreDropped() {
		FrameCryptor encryptor = encryptor(alice);
		FrameCryptor decryptor = decryptor(bob);

		byte[][] frames = new byte[200][];

		for (int i = 0; i < frames.length; i++) {
			frames[i] = process(encryptor, SSRC, "audio/opus", false, false, frame(80));
		}

		assertNotNull(process(decryptor, SSRC, "audio/opus", false, false, frames[0]));
		assertNull(process(decryptor, SSRC, "audio/opus", false, false, frames[0]));
		assertEquals(CryptoFailure.REPLAY, last(bobEvents).getFailure());

		// Late, but within the window, and not seen before.
		assertNotNull(process(decryptor, SSRC, "audio/opus", false, false, frames[150]));
		assertNotNull(process(decryptor, SSRC, "audio/opus", false, false, frames[100]));
		assertNull(process(decryptor, SSRC, "audio/opus", false, false, frames[100]));

		// Further back than the window of 128 frames.
		assertNull(process(decryptor, SSRC, "audio/opus", false, false, frames[10]));

		assertEquals(3, bob.getStats().getDroppedFrames(CryptoFailure.REPLAY));

		// The same key set again keeps what was seen.
		bob.setDecryptionKey(ALICE, 0, aliceKey);
		assertNull(process(decryptor, SSRC, "audio/opus", false, false, frames[150]));
	}

	@Test
	void replayWindowCanBeTurnedOff() {
		KeyProvider keys = KeyProvider.builder(BOB).replayWindow(0).build();
		keys.setDecryptionKey(ALICE, 0, aliceKey);

		byte[] sealed = process(encryptor(alice), SSRC, "audio/opus", false, false, frame(80));
		FrameCryptor decryptor = decryptor(keys);

		assertNotNull(process(decryptor, SSRC, "audio/opus", false, false, sealed));
		assertNotNull(process(decryptor, SSRC, "audio/opus", false, false, sealed));
	}

	@Test
	void tamperedFramesAreDropped() {
		FrameCryptor encryptor = encryptor(alice);
		FrameCryptor decryptor = decryptor(bob);

		byte[] frame = frame(300);
		byte[] sealed = process(encryptor, SSRC, "video/VP8", true, false, frame);

		// The clear header is authenticated too.
		for (int i : new int[] { 0, 2, 20, sealed.length - 1 }) {
			byte[] tampered = sealed.clone();
			tampered[i] ^= 0x04;

			assertNull(process(decryptor, SSRC, "video/VP8", true, false, tampered), "byte " + i);
		}

		// Still decrypts afterwards: forged frames do not move the window.
		assertArrayEquals(frame, process(decryptor, SSRC, "video/VP8", true, false, sealed));
		assertTrue(bob.getStats().getDroppedFrames(CryptoFailure.AUTHENTICATION) >= 3);
	}

	@Test
	void unencryptedFramesAreDropped() {
		FrameCryptor decryptor = decryptor(bob);

		// A clear frame reads as a header of some key ID, or as none.
		assertNull(process(decryptor, SSRC, "audio/opus", false, false, frame(100)));
		assertNull(process(decryptor, SSRC, "video/VP8", true, false, new byte[2]));
		assertNull(process(decryptor, SSRC, "video/AV1", true, false, frame(50)));

		assertEquals(3, bob.getStats().getDroppedFrames());
		assertEquals(0, bob.getStats().getDecryptedFrames());
	}

	@Test
	void streamsHaveOwnKeyIds() {
		FrameCryptor encryptor = encryptor(alice);
		FrameCryptor other = encryptor(alice);

		long[] audio = header(process(encryptor, SSRC, "audio/opus", false, false, frame(50)), 1);
		long[] video = header(process(encryptor, SSRC + 1, "video/VP9", true, true, frame(50)), 0);
		long[] second = header(process(other, SSRC + 2, "video/VP9", true, true, frame(50)), 0);

		// One participant, one generation, three contexts.
		for (long[] header : new long[][] { audio, video, second }) {
			assertEquals(ALICE, FrameFormat.participant(header[0]));
			assertEquals(0, FrameFormat.generation(header[0]));
			assertEquals(0, header[1]);
		}

		assertNotEquals(audio[0], video[0]);
		assertNotEquals(video[0], second[0]);
		assertNotEquals(audio[0], second[0]);

		// Each counts its own frames.
		assertEquals(1, header(process(encryptor, SSRC, "audio/opus", false, false, frame(50)), 1)[1]);
		assertEquals(1, header(process(encryptor, SSRC + 1, "video/VP9", true, false, frame(50)), 0)[1]);
	}

	@Test
	void rotationSwitchesKeys() {
		AtomicInteger keyFrames = new AtomicInteger();
		FrameCryptor encryptor = (FrameCryptor) alice.createEncryptor(keyFrames::incrementAndGet);
		FrameCryptor decryptor = decryptor(bob);

		byte[] old = process(encryptor, SSRC, "video/VP8", true, true, frame(100));
		byte[] oldLate = process(encryptor, SSRC, "video/VP8", true, false, frame(100));
		assertEquals(0, keyFrames.get());

		byte[] newKey = alice.generateKey();
		bob.setDecryptionKey(ALICE, 1, newKey);
		alice.setEncryptionKey(1, newKey);

		assertEquals(1, alice.getEncryptionGeneration());

		byte[] frame = frame(100);
		byte[] rotated = process(encryptor, SSRC, "video/VP8", true, false, frame);

		// The first frame with the new key asks the encoder for a key frame.
		assertEquals(1, keyFrames.get());
		assertEquals(1, FrameFormat.generation(header(rotated, 3)[0]));
		// The counter goes on rather than starting over.
		assertEquals(2, header(rotated, 3)[1]);

		assertNotNull(process(decryptor, SSRC, "video/VP8", true, true, old));
		assertArrayEquals(frame, process(decryptor, SSRC, "video/VP8", true, false, rotated));

		// A frame of the old generation on its way still decrypts.
		assertNotNull(process(decryptor, SSRC, "video/VP8", true, false, oldLate));
	}

	@Test
	void oldGenerationsRetire() throws InterruptedException {
		KeyProvider receiver = KeyProvider.builder(BOB).keyRetention(Duration.ZERO).listener(bobEvents::add).build();
		FrameCryptor encryptor = encryptor(alice);
		FrameCryptor decryptor = decryptor(receiver);

		byte[] newKey = alice.generateKey();
		receiver.setDecryptionKey(ALICE, 0, aliceKey);
		receiver.setDecryptionKey(ALICE, 1, newKey);

		byte[] old = process(encryptor, SSRC, "audio/opus", false, false, frame(50));
		byte[] oldLate = process(encryptor, SSRC, "audio/opus", false, false, frame(50));

		assertNotNull(process(decryptor, SSRC, "audio/opus", false, false, old));

		alice.setEncryptionKey(1, newKey);
		assertNotNull(process(decryptor, SSRC, "audio/opus", false, false,
				process(encryptor, SSRC, "audio/opus", false, false, frame(50))));

		Thread.sleep(2);

		// Generation 0 retired once generation 1 decrypted.
		assertNull(process(decryptor, SSRC, "audio/opus", false, false, oldLate));
		assertEquals(CryptoFailure.UNKNOWN_KEY, last(bobEvents).getFailure());
		assertEquals(0, last(bobEvents).getGeneration());
	}

	@Test
	void keyIdHasFourFields() {
		long kid = FrameFormat.kid(0xABCD, 0xFEDC_BA98L, 0x76, 0x54);

		assertEquals(0xABCD_FEDC_BA98_7654L, kid);
		assertEquals(0xABCD, FrameFormat.context(kid));
		assertEquals(0xFEDC_BA98L, FrameFormat.participant(kid));
		assertEquals(0x76, FrameFormat.generation(kid));
		assertEquals(0x54, FrameFormat.ratchetStep(kid));

		// What goes out: the ratchet step is always 0 in this version.
		byte[] sealed = process(encryptor(alice), SSRC, "video/VP9", true, true, frame(50));
		long[] header = header(sealed, 0);

		assertEquals(FrameFormat.kid(0, ALICE, 0, 0), header[0]);
	}

	@Test
	void ratchetedKeysAreUnknown() {
		// A frame under a key moved forward once, as a later version may
		// send: its key is not there, however well it would authenticate.
		long kid = FrameFormat.kid(0, ALICE, 0, 1);
		SFrame sframe = SFrame.derive(alice.getCipherSuite(), kid, aliceKey);

		byte[] plaintext = frame(100);
		byte[] sealed = new byte[SFrame.MAX_HEADER_LENGTH + plaintext.length + 16];
		int length = sframe.encrypt(0, new byte[0], 0, 0, plaintext, 0, plaintext.length, sealed, 0);

		assertNull(process(decryptor(bob), SSRC, "video/VP9", true, true, Arrays.copyOf(sealed, length)));

		CryptoEvent event = last(bobEvents);
		assertEquals(CryptoFailure.UNKNOWN_KEY, event.getFailure());
		assertEquals(ALICE, event.getParticipant());
		assertEquals(0, event.getGeneration());
	}

	@Test
	void contextsRunOut() {
		KeyProvider keys = KeyProvider.builder(ALICE).build();

		for (int i = 0; i <= FrameFormat.MAX_CONTEXT; i++) {
			assertEquals(i, keys.allocateContext());
		}

		// 65,536 streams in all, and then none.
		assertEquals(-1, keys.allocateContext());
		assertEquals(-1, keys.allocateContext());
	}

	@Test
	void generationsWrapAround() {
		assertTrue(KeyProvider.isNewer(1, 0));
		assertTrue(KeyProvider.isNewer(0, 255));
		assertTrue(KeyProvider.isNewer(100, 0));
		assertFalse(KeyProvider.isNewer(0, 1));
		assertFalse(KeyProvider.isNewer(5, 5));
		assertFalse(KeyProvider.isNewer(200, 0));
	}

	@Test
	void keyRolesAreSeparate() {
		assertThrows(IllegalArgumentException.class, () -> alice.setDecryptionKey(ALICE, 0, aliceKey));
		assertThrows(IllegalArgumentException.class, () -> alice.setEncryptionKey(256, aliceKey));
		assertThrows(IllegalArgumentException.class, () -> alice.setEncryptionKey(0, new byte[15]));
		assertThrows(IllegalArgumentException.class, () -> KeyProvider.builder(-1));
		assertThrows(IllegalArgumentException.class, () -> KeyProvider.builder(1L << 32));

		// Neither encrypts with the other's key, nor decrypts its own frames.
		byte[] sealed = process(encryptor(alice), SSRC, "audio/opus", false, false, frame(50));
		assertNull(process(decryptor(alice), SSRC, "audio/opus", false, false, sealed));

		assertTrue(bob.removeParticipant(ALICE));
		assertNull(process(decryptor(bob), SSRC, "audio/opus", false, false, sealed));
	}

	@Test
	void recordingPolicyFollowsBuilder() {
		assertFalse(alice.createEncryptor().allowsPlaintextObservers());
		assertFalse(alice.createDecryptor().allowsPlaintextObservers());

		KeyProvider open = KeyProvider.builder(ALICE).allowPlaintextRecording(true).build();

		assertTrue(open.createEncryptor().allowsPlaintextObservers());
		assertTrue(open.createDecryptor().allowsPlaintextObservers());
	}

	@Test
	void listenerFailureDoesNotStopFrames() {
		KeyProvider keys = KeyProvider.builder(ALICE).listener(event -> {
			throw new IllegalStateException("listener");
		}).build();

		List<Throwable> uncaught = new ArrayList<>();
		Thread thread = Thread.currentThread();
		Thread.UncaughtExceptionHandler previous = thread.getUncaughtExceptionHandler();
		thread.setUncaughtExceptionHandler((t, e) -> uncaught.add(e));

		try {
			assertNull(process(encryptor(keys), SSRC, "audio/opus", false, false, frame(50)));
		}
		finally {
			thread.setUncaughtExceptionHandler(previous);
		}

		assertEquals(1, uncaught.size());
		assertEquals(1, keys.getStats().getDroppedFrames(CryptoFailure.MISSING_KEY));
	}

	private static FrameCryptor encryptor(KeyProvider keys) {
		return (FrameCryptor) keys.createEncryptor();
	}

	private static FrameCryptor decryptor(KeyProvider keys) {
		return (FrameCryptor) keys.createDecryptor();
	}

	private static byte[] process(FrameCryptor cryptor, long ssrc, String mimeType, boolean video,
			boolean keyFrame, byte[] frame) {
		byte[] out = new byte[frame.length + FrameFormat.MAX_OVERHEAD];
		int length = cryptor.process(ssrc, mimeType, video, keyFrame, frame.clone(), frame.length, out);

		return length < 0 ? null : Arrays.copyOf(out, length);
	}

	/** Parses the SFrame header after the given clear bytes. */
	private static long[] header(byte[] sealed, int clear) {
		long[] header = new long[2];

		assertTrue(SFrame.parseHeader(sealed, clear, sealed.length - clear, header) > 0);

		return header;
	}

	private byte[] frame(int size) {
		byte[] frame = new byte[size];
		random.nextBytes(frame);

		return frame;
	}

	private static CryptoEvent last(List<CryptoEvent> events) {
		assertFalse(events.isEmpty());

		return events.get(events.size() - 1);
	}

	private static byte[] leb128(int value) {
		byte[] bytes = new byte[5];
		int n = 0;

		do {
			int b = value & 0x7F;
			value >>>= 7;
			bytes[n++] = (byte) (value != 0 ? b | 0x80 : b);
		}
		while (value != 0);

		return Arrays.copyOf(bytes, n);
	}

	private static byte[] concat(byte[]... parts) {
		int size = 0;

		for (byte[] part : parts) {
			size += part.length;
		}

		byte[] all = new byte[size];
		int offset = 0;

		for (byte[] part : parts) {
			System.arraycopy(part, 0, all, offset, part.length);
			offset += part.length;
		}

		return all;
	}

}
