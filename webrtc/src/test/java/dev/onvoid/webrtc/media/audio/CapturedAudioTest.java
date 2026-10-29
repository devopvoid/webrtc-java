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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.onvoid.webrtc.PeerConnectionFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The capture stream of an {@link AudioProcessing} after all processing,
 * taken by a sink, which is what a factory's senders send.
 */
class CapturedAudioTest {

	private final List<Captured> captured = new CopyOnWriteArrayList<>();

	private final AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) ->
			captured.add(new Captured(data, bitsPerSample, sampleRate, channels, frames));

	private AudioProcessing processing;


	@BeforeEach
	void createProcessing() {
		processing = new AudioProcessing();
	}

	@AfterEach
	void disposeProcessing() {
		processing.dispose();
	}

	@Test
	void sinkGetsProcessedStream() {
		// A fixed gain makes what was processed differ from what went in.
		AudioProcessingConfig config = new AudioProcessingConfig();
		config.gainControllerDigital.enabled = true;
		config.gainControllerDigital.fixedDigital.gainDb = 6;
		processing.applyConfig(config);
		processing.setCapturedAudioSink(sink);

		AudioProcessingStreamConfig format = new AudioProcessingStreamConfig(48000, 1);
		byte[] input = tone(48000, 1);
		byte[] output = new byte[input.length];

		assertEquals(0, processing.processStream(input, format, format, output));

		assertEquals(1, captured.size());

		Captured chunk = captured.get(0);

		assertEquals(16, chunk.bitsPerSample);
		assertEquals(48000, chunk.sampleRate);
		assertEquals(1, chunk.channels);
		assertEquals(480, chunk.frames);
		// Exactly what the stream puts out, after the gain.
		assertArrayEquals(output, chunk.data);
		assertFalse(Arrays.equals(input, chunk.data));
	}

	@Test
	void sinkGetsProcessingRate() {
		processing.setCapturedAudioSink(sink);

		AudioProcessingStreamConfig format = new AudioProcessingStreamConfig(16000, 1);
		byte[] input = tone(16000, 1);

		processing.processStream(input, format, format, new byte[input.length]);

		Captured chunk = captured.get(0);

		assertEquals(16000, chunk.sampleRate);
		assertEquals(160, chunk.frames);
		assertEquals(chunk.frames * chunk.channels * 2, chunk.data.length);
	}

	@Test
	void sinkRemoved() {
		AudioProcessingStreamConfig format = new AudioProcessingStreamConfig(48000, 1);
		byte[] input = tone(48000, 1);

		// None set yet.
		processing.processStream(input, format, format, new byte[input.length]);

		processing.setCapturedAudioSink(sink);
		processing.processStream(input, format, format, new byte[input.length]);

		processing.setCapturedAudioSink(null);
		processing.processStream(input, format, format, new byte[input.length]);

		assertEquals(1, captured.size());
	}

	@Test
	void factoryTakesSinkOfItsProcessing() {
		HeadlessAudioDeviceModule module = new HeadlessAudioDeviceModule();
		PeerConnectionFactory factory = new PeerConnectionFactory(module, processing);

		try {
			factory.setCapturedAudioSink(sink);

			// What the factory's audio processing processes, as it would the
			// microphone's audio.
			AudioProcessingStreamConfig format = new AudioProcessingStreamConfig(48000, 1);
			byte[] input = tone(48000, 1);
			processing.processStream(input, format, format, new byte[input.length]);

			assertEquals(1, captured.size());

			factory.setCapturedAudioSink(null);
			processing.processStream(input, format, format, new byte[input.length]);

			assertEquals(1, captured.size());
		}
		finally {
			factory.dispose();
			module.dispose();
		}
	}

	@Test
	void factoryWithOwnProcessing() {
		HeadlessAudioDeviceModule module = new HeadlessAudioDeviceModule();
		PeerConnectionFactory factory = new PeerConnectionFactory(module);

		try {
			factory.setCapturedAudioSink(sink);
			factory.setCapturedAudioSink(null);
			factory.setCapturedAudioSink(sink);
		}
		finally {
			// Releases the sink still set along with the module.
			factory.dispose();
			module.dispose();
		}

		assertThrows(NullPointerException.class, () -> factory.setCapturedAudioSink(sink));
	}

	@Test
	void processingOutlivesItsObject() {
		// The factory keeps the module, and with it the sink, after the
		// application disposed of its object.
		AudioProcessing shared = new AudioProcessing();
		HeadlessAudioDeviceModule module = new HeadlessAudioDeviceModule();
		PeerConnectionFactory factory = new PeerConnectionFactory(module, shared);

		try {
			shared.dispose();

			factory.setCapturedAudioSink(sink);
			factory.setCapturedAudioSink(null);

			assertThrows(NullPointerException.class, () -> shared.setCapturedAudioSink(sink));
		}
		finally {
			factory.dispose();
			module.dispose();
		}
	}

	/** 10 ms of a 440 Hz tone. */
	private static byte[] tone(int sampleRate, int channels) {
		int frames = sampleRate / 100;
		byte[] pcm = new byte[frames * channels * 2];

		for (int i = 0; i < frames; i++) {
			short sample = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 4000);

			for (int channel = 0; channel < channels; channel++) {
				int offset = (i * channels + channel) * 2;
				pcm[offset] = (byte) sample;
				pcm[offset + 1] = (byte) (sample >> 8);
			}
		}

		return pcm;
	}



	private static final class Captured {

		final byte[] data;
		final int bitsPerSample;
		final int sampleRate;
		final int channels;
		final int frames;


		Captured(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
			this.data = data.clone();
			this.bitsPerSample = bitsPerSample;
			this.sampleRate = sampleRate;
			this.channels = channels;
			this.frames = frames;
		}
	}
}
