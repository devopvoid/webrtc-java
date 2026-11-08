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

import static java.util.Objects.requireNonNull;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

import dev.onvoid.webrtc.RTCEncodedFrameTransformer;

/**
 * The keys of an end-to-end encrypted call, and the transforms that encrypt
 * and decrypt its frames with them, in the SFrame format of RFC 9605.
 * <p>
 * Every participant of a call has an ID, unique within the call, and a key of
 * its own that it encrypts with and the others decrypt with (the sender keys
 * of RFC 9605, Section 5.1). A key belongs to a <em>generation</em>, 0 to
 * 255, which goes up with every new key and wraps around. Getting the keys to
 * the other participants, over a channel the media servers cannot read, is
 * up to the application; this class only holds them:
 * <pre>{@code
 * KeyProvider keys = KeyProvider.builder(myId).build();
 *
 * byte[] myKey = keys.generateKey();
 * keys.setEncryptionKey(0, myKey);
 * // send (myId, 0, myKey) to the others; for each key they send:
 * keys.setDecryptionKey(theirId, theirGeneration, theirKey);
 *
 * sender.setTransform(keys.createEncryptor(sender::generateKeyFrame));
 * receiver.setTransform(keys.createDecryptor(receiver::requestKeyFrame));
 * }</pre>
 * <p>
 * How it behaves:
 * <ul>
 * <li>Each RTP stream an encryptor sends gets a context of its own, and so a
 * key ID and an encryption key of its own derived from the participant's
 * key: audio, video and every simulcast layer count their frames
 * independently, and no two of them, nor two participants, ever encrypt
 * under the same key and nonce.</li>
 * <li>To rotate its key, a participant sends the new one to the others
 * first, and calls {@link #setEncryptionKey} once they have it: frames go
 * out with the new key from then on, and a video stream begins with a key
 * frame. A receiver keeps the previous generations of a participant until
 * {@link Builder#keyRetention a while} after a frame of a newer one
 * decrypted.</li>
 * <li>A received frame whose key is not there, that does not authenticate,
 * or that repeats a counter is dropped and never reaches the decoder, see
 * {@link CryptoFailure}. A video receiver asks for a key frame once frames
 * decrypt again.</li>
 * <li>A frame that cannot be encrypted is dropped, never sent in the
 * clear.</li>
 * <li>The transforms do not let {@code MediaRecorder}, or any other observer
 * of encoded frames, see the decrypted media unless {@link
 * Builder#allowPlaintextRecording} says so.</li>
 * </ul>
 * <p>
 * All methods are thread-safe, and key changes take effect with the next
 * frame.
 *
 * @author Alex Andres
 */
public final class KeyProvider {

	/**
	 * The name of the frame format the transforms write and read. Nothing in
	 * a frame says which format it is in, so the participants of a call agree
	 * on it by their signaling, along with the cipher suite and the keys.
	 */
	public static final String FORMAT = "webrtc-java-sframe/1";

	/** The smallest base key accepted, in bytes. */
	static final int MIN_KEY_LENGTH = 16;

	private final CipherSuite suite;

	private final long participant;

	private final int replayWindow;

	private final long keyRetentionNanos;

	private final boolean plaintextRecording;

	private final CryptoListener listener;

	private final SecureRandom random = new SecureRandom();

	private volatile LocalKey encryptionKey;

	private final ConcurrentHashMap<Long, RemoteParticipant> participants = new ConcurrentHashMap<>();

	/** The next context to give out. Never reused, so neither is a key ID. */
	private final AtomicInteger contexts = new AtomicInteger();

	private final LongAdder encrypted = new LongAdder();

	private final LongAdder decrypted = new LongAdder();

	private final Map<CryptoFailure, LongAdder> dropped = new EnumMap<>(CryptoFailure.class);


	private KeyProvider(Builder builder) {
		this.suite = builder.suite;
		this.participant = builder.participant;
		this.replayWindow = builder.replayWindow;
		this.keyRetentionNanos = builder.keyRetention.toNanos();
		this.plaintextRecording = builder.plaintextRecording;
		this.listener = builder.listener;

		for (CryptoFailure failure : CryptoFailure.values()) {
			dropped.put(failure, new LongAdder());
		}
	}

	/**
	 * Starts a key provider for the local participant of a call.
	 *
	 * @param participant The ID of the local participant, unique within the
	 *                    call, from 0 to 2<sup>32</sup>-1.
	 *
	 * @return A builder using AES-128-GCM.
	 *
	 * @throws IllegalArgumentException If the ID is out of range.
	 */
	public static Builder builder(long participant) {
		return new Builder(checkParticipant(participant));
	}

	/**
	 * Says whether frames of a codec can be encrypted: those of VP8, VP9, AV1
	 * and every audio codec. H.264 and H.265 cannot, since their packetizers
	 * split frames at start codes that a ciphertext would contain anywhere,
	 * so an encrypted call has to negotiate them away, e.g. with {@code
	 * RTCRtpTransceiver.setCodecPreferences}.
	 *
	 * @param mimeType The codec as a MIME type, e.g. {@code video/VP8}.
	 *
	 * @return True if its frames can be encrypted.
	 */
	public static boolean supportsCodec(String mimeType) {
		return FrameFormat.Codec.of(mimeType) != null;
	}

	/**
	 * Makes a new random key, as long as the cipher suite's hash output. A
	 * key is meant for one call: never use it again with another key
	 * provider, since the counters of a new provider start over.
	 *
	 * @return The key.
	 */
	public byte[] generateKey() {
		byte[] key = new byte[suite.getHashLength()];
		random.nextBytes(key);

		return key;
	}

	/**
	 * Sets the key the local participant encrypts with from now on, of the
	 * given generation. The other participants should have it before, or
	 * they drop the frames until it arrives. Setting a generation again
	 * replaces its key.
	 *
	 * @param generation The key generation, 0 to 255.
	 * @param key        The key, at least 16 bytes; it is copied.
	 *
	 * @throws IllegalArgumentException If the generation is out of range, or
	 *                                  the key too short.
	 */
	public void setEncryptionKey(int generation, byte[] key) {
		checkGeneration(generation);

		encryptionKey = new LocalKey(generation, copyKey(key));
	}

	/**
	 * Removes the encryption key, after which every frame to be sent is
	 * dropped, e.g. while the participant is not allowed to send.
	 */
	public void removeEncryptionKey() {
		encryptionKey = null;
	}

	/**
	 * @return The generation of the encryption key, or -1 if there is none.
	 */
	public int getEncryptionGeneration() {
		LocalKey key = encryptionKey;

		return key == null ? -1 : key.generation;
	}

	/**
	 * Sets a key that frames of another participant are decrypted with. A
	 * participant's generations are kept side by side, so that a new key can
	 * be set before the participant starts to use it; setting a generation
	 * again with another key replaces it.
	 *
	 * @param participant The ID of the participant.
	 * @param generation  The key generation, 0 to 255.
	 * @param key         The key, at least 16 bytes; it is copied.
	 *
	 * @throws IllegalArgumentException If the participant is the local one,
	 *                                  whose key is only for encryption, if
	 *                                  a value is out of range, or the key
	 *                                  too short.
	 */
	public void setDecryptionKey(long participant, int generation, byte[] key) {
		checkParticipant(participant);
		checkGeneration(generation);

		if (participant == this.participant) {
			throw new IllegalArgumentException("A key of the local participant is only for encryption");
		}

		byte[] copy = copyKey(key);
		RemoteParticipant remote = participants.computeIfAbsent(participant, id -> new RemoteParticipant());

		synchronized (remote) {
			RemoteKey current = remote.keys.get(generation);

			if (current != null && Arrays.equals(current.baseKey, copy)) {
				// The same key again, e.g. sent once more by the signaling:
				// keeps the frames seen, so that none can be replayed.
				current.retireAt = 0;
				return;
			}

			remote.keys.set(generation, new RemoteKey(generation, copy));
		}
	}

	/**
	 * Removes a decryption key. Frames of that generation are dropped from
	 * now on.
	 *
	 * @param participant The ID of the participant.
	 * @param generation  The key generation.
	 *
	 * @return True if there was such a key.
	 */
	public boolean removeDecryptionKey(long participant, int generation) {
		RemoteParticipant remote = participants.get(participant);

		if (remote == null || generation < 0 || generation > FrameFormat.MAX_GENERATION) {
			return false;
		}

		return remote.keys.getAndSet(generation, null) != null;
	}

	/**
	 * Removes every decryption key of a participant, e.g. one that left.
	 *
	 * @param participant The ID of the participant.
	 *
	 * @return True if there were keys of the participant.
	 */
	public boolean removeParticipant(long participant) {
		return participants.remove(participant) != null;
	}

	/**
	 * Creates a transform for senders that encrypts their frames. One
	 * encryptor may serve several senders, but each sender with an encryptor
	 * of its own can be asked for a key frame when the key changes.
	 *
	 * @return The encryptor.
	 */
	public RTCEncodedFrameTransformer createEncryptor() {
		return new FrameEncryptor(this, null);
	}

	/**
	 * Creates a transform for a sender that encrypts its frames, and asks the
	 * sender's encoder for a key frame when a video stream starts to use a
	 * new key, or once its frames can be sent again, so that receivers that
	 * just got the key need not wait for one.
	 *
	 * @param keyFrameRequest Asks the sender for a key frame, e.g. {@code
	 *                        sender::generateKeyFrame}. It runs on the
	 *                        transform's thread.
	 *
	 * @return The encryptor.
	 */
	public RTCEncodedFrameTransformer createEncryptor(Runnable keyFrameRequest) {
		return new FrameEncryptor(this, requireNonNull(keyFrameRequest, "keyFrameRequest"));
	}

	/**
	 * Creates a transform for receivers that decrypts their frames.
	 *
	 * @return The decryptor.
	 */
	public RTCEncodedFrameTransformer createDecryptor() {
		return new FrameDecryptor(this, null);
	}

	/**
	 * Creates a transform for a receiver that decrypts its frames, and asks
	 * the remote sender for a key frame once video frames decrypt again after
	 * some were dropped, since the decoder cannot use the frames that follow
	 * a missing one.
	 *
	 * @param keyFrameRequest Asks for a key frame, e.g. {@code
	 *                        receiver::requestKeyFrame}. It runs on the
	 *                        transform's thread.
	 *
	 * @return The decryptor.
	 */
	public RTCEncodedFrameTransformer createDecryptor(Runnable keyFrameRequest) {
		return new FrameDecryptor(this, requireNonNull(keyFrameRequest, "keyFrameRequest"));
	}

	/**
	 * @return The frames encrypted, decrypted and dropped so far.
	 */
	public CryptoStats getStats() {
		Map<CryptoFailure, Long> counts = new EnumMap<>(CryptoFailure.class);

		dropped.forEach((failure, count) -> counts.put(failure, count.sum()));

		return new CryptoStats(encrypted.sum(), decrypted.sum(), counts);
	}

	/**
	 * @return The ID of the local participant.
	 */
	public long getParticipant() {
		return participant;
	}

	/**
	 * @return The cipher suite of the call.
	 */
	public CipherSuite getCipherSuite() {
		return suite;
	}

	/**
	 * @return Whether observers of encoded frames, such as a recorder, may
	 * see what the transforms protect.
	 */
	public boolean allowsPlaintextRecording() {
		return plaintextRecording;
	}

	LocalKey encryptionKey() {
		return encryptionKey;
	}

	/**
	 * Gives out a context for a new stream.
	 *
	 * @return The context, or -1 if all were given out.
	 */
	int allocateContext() {
		int context = contexts.getAndUpdate(c -> c > FrameFormat.MAX_CONTEXT ? c : c + 1);

		return context > FrameFormat.MAX_CONTEXT ? -1 : context;
	}

	/**
	 * Looks up the decryption key of a key ID, and retires it if its time is
	 * up.
	 */
	RemoteKey decryptionKey(long participant, int generation, long now) {
		RemoteParticipant remote = participants.get(participant);

		if (remote == null) {
			return null;
		}

		RemoteKey key = remote.keys.get(generation);

		if (key != null && key.retireAt != 0 && now - key.retireAt > 0) {
			remote.keys.compareAndSet(generation, key, null);

			return null;
		}

		return key;
	}

	/**
	 * Notes that a frame decrypted with a key: once a generation newer than
	 * any before is in use, the older ones retire after the retention time.
	 */
	void decryptedWith(long participant, RemoteKey key, long now) {
		decrypted.increment();

		RemoteParticipant remote = participants.get(participant);

		if (remote == null || remote.newest == key.generation) {
			return;
		}

		synchronized (remote) {
			if (remote.newest != -1 && !isNewer(key.generation, remote.newest)) {
				return;
			}

			remote.newest = key.generation;

			long retireAt = now + keyRetentionNanos;

			if (retireAt == 0) {
				retireAt = 1;
			}

			for (int g = 0; g <= FrameFormat.MAX_GENERATION; g++) {
				RemoteKey other = remote.keys.get(g);

				if (other != null && other != key && other.retireAt == 0 && isNewer(key.generation, g)) {
					other.retireAt = retireAt;
				}
			}
		}
	}

	void encrypted() {
		encrypted.increment();
	}

	void dropped(CryptoFailure failure) {
		dropped.get(failure).increment();
	}

	int replayWindow() {
		return replayWindow;
	}

	void report(CryptoEvent event) {
		CryptoListener current = listener;

		if (current == null) {
			return;
		}

		try {
			current.onFailure(event);
		}
		catch (Throwable e) {
			Thread thread = Thread.currentThread();

			try {
				thread.getUncaughtExceptionHandler().uncaughtException(thread, e);
			}
			catch (Throwable ignored) {
				// Nothing more can be done about it here.
			}
		}
	}

	/**
	 * Whether generation a came after b, given that generations wrap around
	 * at 256 and at most 127 of them are in use at a time.
	 */
	static boolean isNewer(int a, int b) {
		int distance = (a - b) & FrameFormat.MAX_GENERATION;

		return distance > 0 && distance < 128;
	}

	private static long checkParticipant(long participant) {
		if (participant < 0 || participant > FrameFormat.MAX_PARTICIPANT) {
			throw new IllegalArgumentException("A participant ID is from 0 to 2^32-1: " + participant);
		}

		return participant;
	}

	private static void checkGeneration(int generation) {
		if (generation < 0 || generation > FrameFormat.MAX_GENERATION) {
			throw new IllegalArgumentException("A key generation is from 0 to 255: " + generation);
		}
	}

	private static byte[] copyKey(byte[] key) {
		requireNonNull(key, "key");

		if (key.length < MIN_KEY_LENGTH) {
			throw new IllegalArgumentException("A key has at least " + MIN_KEY_LENGTH + " bytes");
		}

		return key.clone();
	}



	/**
	 * Configures a {@link KeyProvider}.
	 */
	public static final class Builder {

		private final long participant;

		private CipherSuite suite = CipherSuite.AES_128_GCM_SHA256_128;

		private int replayWindow = 128;

		private Duration keyRetention = Duration.ofSeconds(5);

		private boolean plaintextRecording;

		private CryptoListener listener;


		private Builder(long participant) {
			this.participant = participant;
		}

		/**
		 * Sets the cipher suite, which every participant has to use alike.
		 * The default is {@link CipherSuite#AES_128_GCM_SHA256_128}.
		 *
		 * @param suite The cipher suite.
		 *
		 * @return This builder.
		 */
		public Builder cipherSuite(CipherSuite suite) {
			this.suite = requireNonNull(suite, "suite");
			return this;
		}

		/**
		 * Sets how far back from the newest frame of a stream a frame that
		 * arrives late is still accepted, in frames; a frame further back,
		 * or one decrypted before, is dropped as a replay. The default of
		 * 128 is several seconds of any stream, more than any reordering.
		 * 0 turns replay protection off.
		 *
		 * @param frames The window, rounded up to a multiple of 64, at most
		 *               65536.
		 *
		 * @return This builder.
		 */
		public Builder replayWindow(int frames) {
			if (frames < 0 || frames > 65536) {
				throw new IllegalArgumentException("The replay window is from 0 to 65536 frames: " + frames);
			}

			this.replayWindow = (frames + 63) / 64 * 64;
			return this;
		}

		/**
		 * Sets how long a participant's older key generations still decrypt
		 * after a frame of a newer generation did, for frames that were on
		 * their way during the switch. The default is 5 seconds.
		 *
		 * @param retention The time, zero or more.
		 *
		 * @return This builder.
		 */
		public Builder keyRetention(Duration retention) {
			requireNonNull(retention, "retention");

			if (retention.isNegative()) {
				throw new IllegalArgumentException("The key retention is negative: " + retention);
			}

			this.keyRetention = retention;
			return this;
		}

		/**
		 * Sets whether observers of encoded frames may see what the
		 * transforms protect: a sender's frames before they are encrypted, a
		 * receiver's after they are decrypted. Such an observer is, above
		 * all, a {@code MediaRecorder}, which stores the media in the clear.
		 * The default is false: a recorder refuses the senders and receivers
		 * of the transforms, and frames are kept from any observer.
		 *
		 * @param allowed Whether to allow it.
		 *
		 * @return This builder.
		 */
		public Builder allowPlaintextRecording(boolean allowed) {
			this.plaintextRecording = allowed;
			return this;
		}

		/**
		 * Sets what hears of streams that begin to drop frames.
		 *
		 * @param listener The listener.
		 *
		 * @return This builder.
		 */
		public Builder listener(CryptoListener listener) {
			this.listener = requireNonNull(listener, "listener");
			return this;
		}

		/**
		 * @return The key provider, without keys yet.
		 */
		public KeyProvider build() {
			return new KeyProvider(this);
		}
	}



	/** The key the local participant encrypts with. */
	static final class LocalKey {

		final int generation;

		private final byte[] baseKey;

		/** The derived key of each context. */
		private final ConcurrentHashMap<Integer, SFrame> derived = new ConcurrentHashMap<>();


		LocalKey(int generation, byte[] baseKey) {
			this.generation = generation;
			this.baseKey = baseKey;
		}

		SFrame sframe(CipherSuite suite, int context, long participant) {
			return derived.computeIfAbsent(context, c ->
					SFrame.derive(suite, FrameFormat.kid(c, participant, generation, 0), baseKey));
		}
	}



	/** A key that frames of another participant are decrypted with. */
	static final class RemoteKey {

		final int generation;

		final byte[] baseKey;

		/** When the key retires, in System.nanoTime(), or 0 while it is in use. */
		volatile long retireAt;

		/** What each context of the participant needs to decrypt. */
		private final ConcurrentHashMap<Integer, Opener> openers = new ConcurrentHashMap<>();


		RemoteKey(int generation, byte[] baseKey) {
			this.generation = generation;
			this.baseKey = baseKey;
		}

		Opener opener(CipherSuite suite, long kid, int window) {
			return openers.computeIfAbsent(FrameFormat.context(kid), c ->
					new Opener(SFrame.derive(suite, kid, baseKey), window > 0 ? new ReplayWindow(window) : null));
		}
	}



	/** The derived key of one key ID, and the counters it has seen. */
	static final class Opener {

		final SFrame sframe;

		/** Null if replay protection is off. */
		final ReplayWindow window;


		Opener(SFrame sframe, ReplayWindow window) {
			this.sframe = sframe;
			this.window = window;
		}
	}



	/** The decryption keys of another participant. */
	private static final class RemoteParticipant {

		final AtomicReferenceArray<RemoteKey> keys = new AtomicReferenceArray<>(FrameFormat.MAX_GENERATION + 1);

		/** The newest generation that decrypted a frame, or -1. */
		volatile int newest = -1;
	}

}
