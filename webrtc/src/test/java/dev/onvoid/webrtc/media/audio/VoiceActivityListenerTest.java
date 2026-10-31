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

package dev.onvoid.webrtc.media.audio;

import static dev.onvoid.webrtc.media.audio.SpeechFixture.silence;
import static dev.onvoid.webrtc.media.audio.SpeechFixture.speech;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Speech detected in native code, in what a track's source hands to sinks
 * and in what a factory's audio processing sends, with only the changes
 * reaching the listener.
 */
class VoiceActivityListenerTest extends TestBase {

	/** Long enough to bridge every pause of the fixture. */
	private static final VoiceActivityConfig ONE_SEGMENT = new VoiceActivityConfig(0.5, Duration.ofMillis(500));

	private final List<Boolean> changes = new CopyOnWriteArrayList<>();

	private final VoiceActivityListener listener = changes::add;

	private CustomAudioSource source;

	private AudioTrack track;


	@BeforeEach
	void createTrack() {
		// TestBase keeps one instance for all tests.
		changes.clear();

		source = new CustomAudioSource();
		track = factory.createAudioTrack("pushed", source);
	}

	@AfterEach
	void disposeTrack() {
		track.dispose();
		source.dispose();
	}

	@Test
	void pushedSpeechBeginsAndEnds() {
		track.addVoiceActivityListener(ONE_SEGMENT, listener);

		push(speech(16000, 1), 16000, 1);

		assertEquals(List.of(true), changes);

		push(silence(1000, 16000, 1), 16000, 1);

		assertEquals(List.of(true, false), changes);
	}

	@Test
	void pushedSpeechAtAnyRate() {
		// The detector takes 16 kHz mono; the rest is mixed and resampled.
		int[][] formats = { { 8000, 1 }, { 44100, 2 }, { 48000, 1 }, { 48000, 2 } };

		for (int[] format : formats) {
			List<Boolean> heard = new CopyOnWriteArrayList<>();
			VoiceActivityListener formatListener = heard::add;

			track.addVoiceActivityListener(ONE_SEGMENT, formatListener);

			push(speech(format[0], format[1]), format[0], format[1]);
			push(silence(1000, format[0], format[1]), format[0], format[1]);

			track.removeVoiceActivityListener(formatListener);

			assertEquals(List.of(true, false), heard, () -> format[0] + " Hz, " + format[1] + " channels");
		}
	}

	@Test
	void hangoverHoldsSpeech() {
		// Both bridge the pauses of the fixture; the longer one ends the
		// speech 500 ms later, give or take the 30 ms the detector rates.
		int shorter = chunksUntilEnd(new VoiceActivityConfig(0.5, Duration.ofMillis(500)));
		int longer = chunksUntilEnd(new VoiceActivityConfig(0.5, Duration.ofMillis(1000)));

		assertTrue(Math.abs(longer - shorter - 50) <= 3, () -> "Ended after " + shorter + " and " + longer
				+ " chunks");
	}

	@Test
	void trackHangoverBoundaries() {
		assertHangoverBoundaries(track::addVoiceActivityListener, pcm -> push(pcm, 16000, 1));
	}

	@Test
	void factoryHangoverBoundaries() {
		AudioProcessing processing = new AudioProcessing();
		HeadlessAudioDeviceModule module = new HeadlessAudioDeviceModule();
		PeerConnectionFactory processed = new PeerConnectionFactory(module, processing);

		try {
			assertHangoverBoundaries(processed::addVoiceActivityListener,
					pcm -> process(processing, pcm, 16000));
		}
		finally {
			processed.dispose();
			module.dispose();
			processing.dispose();
		}
	}

	private void assertHangoverBoundaries(
			BiConsumer<VoiceActivityConfig, VoiceActivityListener> addListener,
			Consumer<byte[]> deliver) {
		Duration[] hangovers = { Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(10),
				Duration.ofMillis(10).plusNanos(1), Duration.ofNanos(10500000), Duration.ofMillis(20) };
		int[] chunk = { 0 };
		int[] lastEnd = new int[hangovers.length];
		Arrays.fill(lastEnd, -1);

		for (int i = 0; i < hangovers.length; i++) {
			final int index = i;
			addListener.accept(new VoiceActivityConfig(0.5, hangovers[i]), speaking -> {
				if (!speaking) {
					lastEnd[index] = chunk[0];
				}
			});
		}

		List<Boolean> maximumChanges = new CopyOnWriteArrayList<>();
		addListener.accept(new VoiceActivityConfig(0.5, Duration.ofMillis(Integer.MAX_VALUE)),
				maximumChanges::add);

		byte[] pcm = SpeechFixture.concat(speech(16000, 1), silence(2000, 16000, 1));
		for (int offset = 0; offset + 320 <= pcm.length; offset += 320) {
			deliver.accept(Arrays.copyOfRange(pcm, offset, offset + 320));
			chunk[0]++;
		}

		assertTrue(lastEnd[0] >= 0, "Speech must end for the rounding comparisons");
		assertEquals(lastEnd[0] + 1, lastEnd[1], "1 ns rounds up to 10 ms");
		assertEquals(lastEnd[1], lastEnd[2], "Exactly 10 ms stays one chunk");
		assertEquals(lastEnd[2] + 1, lastEnd[3], "10 ms + 1 ns rounds up to 20 ms");
		assertEquals(lastEnd[3], lastEnd[4], "10.5 ms rounds up to 20 ms");
		assertEquals(lastEnd[4], lastEnd[5], "Exactly 20 ms stays two chunks");
		assertEquals(List.of(true), maximumChanges, "The maximum hangover must keep speech active");
	}

	@Test
	void shortHangoverSplitsSpeech() {
		// Without a hangover, the pauses between words end speech.
		track.addVoiceActivityListener(new VoiceActivityConfig(0.5, Duration.ZERO), listener);

		push(speech(16000, 1), 16000, 1);
		push(silence(100, 16000, 1), 16000, 1);

		assertAlternates(changes);
		assertTrue(changes.size() > 2, () -> "Expected the speech split, got " + changes);
	}

	@Test
	void thresholdAboveSpeech() {
		// The detector rates no audio at 1.
		track.addVoiceActivityListener(new VoiceActivityConfig(1, Duration.ZERO), listener);

		push(speech(16000, 1), 16000, 1);

		assertEquals(List.of(), changes);
	}

	@Test
	void silenceIsNoSpeech() {
		// Not even the detector's initial probability of 1 counts.
		track.addVoiceActivityListener(new VoiceActivityConfig(0.02, Duration.ZERO), listener);

		push(silence(500, 16000, 1), 16000, 1);

		assertEquals(List.of(), changes);
	}

	@Test
	void eachListenerWithItsConfig() {
		List<Boolean> other = new CopyOnWriteArrayList<>();

		track.addVoiceActivityListener(ONE_SEGMENT, listener);
		track.addVoiceActivityListener(new VoiceActivityConfig(1, Duration.ZERO), other::add);

		push(speech(16000, 1), 16000, 1);
		push(silence(1000, 16000, 1), 16000, 1);

		assertEquals(List.of(true, false), changes);
		assertEquals(List.of(), other);
	}

	@Test
	void removedListenerHearsNothing() {
		track.addVoiceActivityListener(ONE_SEGMENT, listener);
		// Adding it again keeps the one.
		track.addVoiceActivityListener(ONE_SEGMENT, listener);
		track.removeVoiceActivityListener(listener);
		track.removeVoiceActivityListener(listener);

		push(speech(16000, 1), 16000, 1);

		assertEquals(List.of(), changes);
	}

	@Test
	void disposeRemovesListeners() {
		AudioTrack other = factory.createAudioTrack("other", source);

		other.addVoiceActivityListener(ONE_SEGMENT, listener);
		other.dispose();

		push(speech(16000, 1), 16000, 1);

		assertEquals(List.of(), changes);
	}

	@Test
	void rejectsNull() {
		assertThrows(NullPointerException.class, () -> track.addVoiceActivityListener(null, listener));
		assertThrows(NullPointerException.class, () -> track.addVoiceActivityListener(ONE_SEGMENT, null));
		assertThrows(NullPointerException.class, () -> track.removeVoiceActivityListener(null));
	}

	@Test
	void factoryHearsItsProcessing() {
		AudioProcessing processing = new AudioProcessing();
		HeadlessAudioDeviceModule module = new HeadlessAudioDeviceModule();
		PeerConnectionFactory processed = new PeerConnectionFactory(module, processing);

		try {
			processed.addVoiceActivityListener(ONE_SEGMENT, listener);

			// What the factory's audio processing processes, as it would the
			// microphone's audio.
			process(processing, speech(48000, 1), 48000);
			process(processing, silence(1000, 48000, 1), 48000);

			assertEquals(List.of(true, false), changes);

			processed.removeVoiceActivityListener(listener);
			process(processing, speech(48000, 1), 48000);

			assertEquals(List.of(true, false), changes);
		}
		finally {
			processed.dispose();
			module.dispose();
			processing.dispose();
		}
	}

	@Test
	void factoryDisposeRemovesListeners() {
		// The application's audio processing outlives the factory.
		AudioProcessing processing = new AudioProcessing();
		HeadlessAudioDeviceModule module = new HeadlessAudioDeviceModule();
		PeerConnectionFactory processed = new PeerConnectionFactory(module, processing);

		try {
			processed.addVoiceActivityListener(ONE_SEGMENT, listener);
		}
		finally {
			processed.dispose();
			module.dispose();
		}

		try {
			process(processing, speech(16000, 1), 16000);

			assertEquals(List.of(), changes);
		}
		finally {
			processing.dispose();
		}
	}

	@Test
	void factoryWithOwnProcessing() {
		HeadlessAudioDeviceModule module = new HeadlessAudioDeviceModule();
		PeerConnectionFactory own = new PeerConnectionFactory(module);

		try {
			own.addVoiceActivityListener(ONE_SEGMENT, listener);
			own.removeVoiceActivityListener(listener);
			own.addVoiceActivityListener(ONE_SEGMENT, listener);
		}
		finally {
			// Removes the listener still added.
			own.dispose();
			module.dispose();
		}

		assertThrows(NullPointerException.class, () -> own.addVoiceActivityListener(ONE_SEGMENT, listener));
	}

	@Test
	void configDefaults() {
		VoiceActivityConfig config = new VoiceActivityConfig();

		assertEquals(VoiceActivityConfig.DEFAULT_THRESHOLD, config.getThreshold());
		assertEquals(VoiceActivityConfig.DEFAULT_HANGOVER, config.getHangover());
	}

	@Test
	void configRejectsOutOfRange() {
		for (double threshold : new double[] { 0, -0.5, 1.01, Double.NaN }) {
			assertThrows(IllegalArgumentException.class, () -> new VoiceActivityConfig(threshold, Duration.ZERO));
		}

		assertThrows(IllegalArgumentException.class, () -> new VoiceActivityConfig(0.5, Duration.ofMillis(-10)));
		assertThrows(IllegalArgumentException.class, () -> new VoiceActivityConfig(0.5, Duration.ofDays(30)));
		assertThrows(IllegalArgumentException.class, () -> new VoiceActivityConfig(0.5,
				Duration.ofMillis(Integer.MAX_VALUE).plusNanos(1)));
		assertThrows(NullPointerException.class, () -> new VoiceActivityConfig(0.5, null));
	}

	/**
	 * @return the 10 ms chunks of speech and silence pushed until the speech
	 * ended.
	 */
	private int chunksUntilEnd(VoiceActivityConfig config) {
		List<Boolean> heard = new CopyOnWriteArrayList<>();
		VoiceActivityListener counting = heard::add;
		byte[] pcm = SpeechFixture.concat(speech(16000, 1), silence(2000, 16000, 1));
		int chunks = 0;

		track.addVoiceActivityListener(config, counting);

		try {
			for (int offset = 0; offset < pcm.length && heard.size() < 2; offset += 320) {
				source.pushAudio(Arrays.copyOfRange(pcm, offset, offset + 320), 16, 16000, 1, 160);
				chunks++;
			}
		}
		finally {
			track.removeVoiceActivityListener(counting);
		}

		assertEquals(List.of(true, false), heard);

		return chunks;
	}

	private void push(byte[] pcm, int sampleRate, int channels) {
		int frames = sampleRate / 100;
		int chunk = frames * channels * 2;

		for (int offset = 0; offset + chunk <= pcm.length; offset += chunk) {
			source.pushAudio(Arrays.copyOfRange(pcm, offset, offset + chunk), 16, sampleRate, channels, frames);
		}
	}

	private static void process(AudioProcessing processing, byte[] pcm, int sampleRate) {
		AudioProcessingStreamConfig format = new AudioProcessingStreamConfig(sampleRate, 1);
		int chunk = sampleRate / 100 * 2;
		byte[] output = new byte[chunk];

		for (int offset = 0; offset + chunk <= pcm.length; offset += chunk) {
			assertEquals(0, processing.processStream(Arrays.copyOfRange(pcm, offset, offset + chunk), format,
					format, output));
		}
	}

	private static void assertAlternates(List<Boolean> changes) {
		for (int i = 0; i < changes.size(); i++) {
			assertEquals(i % 2 == 0, changes.get(i), () -> "Expected alternating changes, got " + changes);
		}
	}
}
