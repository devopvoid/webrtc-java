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

import static dev.onvoid.webrtc.media.audio.SpeechFixture.concat;
import static dev.onvoid.webrtc.media.audio.SpeechFixture.silence;
import static dev.onvoid.webrtc.media.audio.SpeechFixture.speech;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioProcessing;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.VoiceActivityConfig;

import org.junit.jupiter.api.Test;

/**
 * Speech in the audio a local track sends, detected in native code: pushed
 * audio as pushed, a microphone's after the runtime's audio processing.
 */
class LocalAudioTests extends ClientTestBase {

	/** Long enough to bridge every pause of the fixture. */
	private static final VoiceActivityConfig ONE_SEGMENT = new VoiceActivityConfig(0.5, Duration.ofMillis(500));


	@Test
	void pushedSpeech() throws Exception {
		CustomAudio audio = rtc.media().customAudio("audio");
		BlockingQueue<Boolean> changes = new LinkedBlockingQueue<>();

		audio.onVoiceActivity(ONE_SEGMENT, changes::add);
		// Sent, and with it analyzed, at the pace of real time.
		audio.push(concat(speech(48000, 1), silence(1000, 48000, 1)), PcmFormat.of(48000, 1));

		assertEquals(Boolean.TRUE, changes.poll(10, TimeUnit.SECONDS));
		assertEquals(Boolean.FALSE, changes.poll(10, TimeUnit.SECONDS));
	}

	@Test
	void microphoneSpeechAfterProcessing() throws Exception {
		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.audioProcessing(new AudioProcessingConfig())
				.errorHandler(errors::add)
				.build();

		try {
			LocalAudio microphone = microphone(runtime);
			BlockingQueue<Boolean> first = new LinkedBlockingQueue<>();
			BlockingQueue<Boolean> second = new LinkedBlockingQueue<>();

			Subscription one = microphone.onVoiceActivity(ONE_SEGMENT, first::add);
			microphone.onVoiceActivity(ONE_SEGMENT, second::add);

			// Stands in for WebRTC, which hands the microphone's audio to the
			// same audio processing before it sends it.
			process(runtime.audioProcessing, concat(speech(48000, 1), silence(1000, 48000, 1)));

			assertEquals(Boolean.TRUE, first.poll(10, TimeUnit.SECONDS));
			assertEquals(Boolean.FALSE, first.poll(10, TimeUnit.SECONDS));
			assertEquals(Boolean.TRUE, second.poll(10, TimeUnit.SECONDS));
			assertEquals(Boolean.FALSE, second.poll(10, TimeUnit.SECONDS));

			// The other subscription keeps hearing.
			one.close();
			process(runtime.audioProcessing, concat(speech(48000, 1), silence(1000, 48000, 1)));

			assertEquals(Boolean.TRUE, second.poll(10, TimeUnit.SECONDS));
			assertEquals(Boolean.FALSE, second.poll(10, TimeUnit.SECONDS));
			// The same executor would have called it before.
			assertNull(first.poll());

			// Closing the track ends its subscriptions.
			await(microphone.closeAsync());

			Subscription late = microphone.onVoiceActivity(speaking -> { });
			late.close();
		}
		finally {
			runtime.close();
		}
	}

	@Test
	void microphoneWithFactorysOwnProcessing() throws Exception {
		// Without a config, the factory builds its audio processing itself;
		// subscribing and closing needs nothing of the application.
		LocalAudio microphone = microphone(rtc);

		Subscription activity = microphone.onVoiceActivity(speaking -> { });
		activity.close();
		microphone.onVoiceActivity(ONE_SEGMENT, speaking -> { });

		await(microphone.closeAsync());

		assertTrue(microphone.isEnded());
	}

	@Test
	void closingTrackEndsSubscriptions() throws Exception {
		CustomAudio audio = rtc.media().customAudio("audio");
		BlockingQueue<Boolean> changes = new LinkedBlockingQueue<>();

		Subscription activity = audio.onVoiceActivity(ONE_SEGMENT, changes::add);

		await(audio.closeAsync());

		// Closing it after the track does nothing more.
		activity.close();
		audio.onVoiceActivity(changes::add).close();

		assertTrue(changes.isEmpty());
	}

	@Test
	void rejectsNull() {
		CustomAudio audio = rtc.media().customAudio("audio");

		assertThrows(NullPointerException.class, () -> audio.onVoiceActivity(null));
		assertThrows(NullPointerException.class, () -> audio.onVoiceActivity(null, speaking -> { }));
		assertThrows(NullPointerException.class, () -> audio.onVoiceActivity(ONE_SEGMENT, null));
	}

	/**
	 * A microphone track of a headless runtime, which records nothing, so
	 * that the test hands its audio processing the audio.
	 */
	private static LocalAudio microphone(WebRtc runtime) {
		LocalMediaImpl media = (LocalMediaImpl) runtime.media();
		AudioTrackSource source = runtime.factory.createAudioSource(new AudioOptions());

		return media.register(new LocalAudioImpl(media,
				runtime.factory.createAudioTrack("microphone", source), source::dispose, true));
	}

	/** Processes 48 kHz mono in 10 ms chunks. */
	private static void process(AudioProcessing processing, byte[] pcm) {
		AudioProcessingStreamConfig format = new AudioProcessingStreamConfig(48000, 1);
		byte[] output = new byte[960];

		for (int offset = 0; offset + 960 <= pcm.length; offset += 960) {
			assertEquals(0, processing.processStream(Arrays.copyOfRange(pcm, offset, offset + 960), format,
					format, output));
		}
	}

}
