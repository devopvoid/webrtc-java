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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig;
import dev.onvoid.webrtc.media.audio.AudioProcessingStreamConfig;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;

import org.junit.jupiter.api.Test;

/**
 * The audio a local track sends: pushed audio as pushed, a microphone's after
 * the runtime's audio processing.
 */
class LocalAudioTests extends ClientTestBase {

	private static final PcmFormat FORMAT = PcmFormat.of(48000, 1);


	@Test
	void pushedAudioArrives() throws Exception {
		CustomAudio audio = rtc.media().customAudio("audio");
		CompletableFuture<AudioChunk> received = new CompletableFuture<>();

		audio.onAudio(received::complete);
		audio.push(constant(4800, 1000), FORMAT);

		AudioChunk chunk = await(received);

		assertEquals(48000, chunk.sampleRate());
		assertEquals(1, chunk.channels());
		assertSamples(1000, chunk);
	}

	@Test
	void microphoneAudioAfterProcessing() throws Exception {
		// A fixed gain makes what was processed differ from what went in.
		AudioProcessingConfig config = new AudioProcessingConfig();
		config.gainControllerDigital.enabled = true;
		config.gainControllerDigital.fixedDigital.gainDb = 6;

		WebRtc runtime = WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.audioProcessing(config)
				.errorHandler(errors::add)
				.build();

		try {
			LocalAudio microphone = microphone(runtime);
			CompletableFuture<AudioChunk> first = new CompletableFuture<>();
			CompletableFuture<AudioChunk> second = new CompletableFuture<>();

			Subscription one = microphone.onAudio(first::complete);
			microphone.onAudio(second::complete);

			// Stands in for WebRTC, which hands the microphone's audio to the
			// same audio processing before it sends it.
			byte[] input = constant(480, 1000);
			byte[] output = new byte[input.length];
			AudioProcessingStreamConfig format = new AudioProcessingStreamConfig(48000, 1);

			assertEquals(0, runtime.audioProcessing.processStream(input, format, format, output));

			AudioChunk chunk = await(first);

			assertEquals(48000, chunk.sampleRate());
			assertEquals(480, chunk.frames());
			assertArrayEquals(output, chunk.samples());
			assertFalse(Arrays.equals(input, chunk.samples()));
			assertArrayEquals(output, await(second).samples());

			// The other subscription keeps the audio coming.
			one.close();

			CompletableFuture<AudioChunk> third = new CompletableFuture<>();
			microphone.onAudio(third::complete);
			runtime.audioProcessing.processStream(input, format, format, output);

			assertArrayEquals(output, await(third).samples());

			// Closing the track ends its subscriptions.
			await(microphone.closeAsync());

			Subscription late = microphone.onAudio(chunkAfter -> { });
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

		Subscription audio = microphone.onAudio(chunk -> { });
		audio.close();
		microphone.onAudio(chunk -> { });

		await(microphone.closeAsync());

		assertTrue(microphone.isEnded());
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

	/** Mono 16-bit PCM with every sample set to the value. */
	private static byte[] constant(int frames, int value) {
		ByteBuffer pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);

		for (int i = 0; i < frames; i++) {
			pcm.putShort((short) value);
		}

		return pcm.array();
	}

	private static void assertSamples(int value, AudioChunk chunk) {
		ByteBuffer samples = ByteBuffer.wrap(chunk.samples()).order(ByteOrder.LITTLE_ENDIAN);

		while (samples.hasRemaining()) {
			assertEquals(value, samples.getShort());
		}
	}

}
