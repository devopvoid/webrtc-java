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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The speech fixture of {@code /audio/vad-speech-16k.pcm}, at any rate and
 * channel count, and silence to follow it.
 */
public final class SpeechFixture {

	private static final short[] SPEECH = load();


	private SpeechFixture() {
	}

	/**
	 * One second of synthetic speech at another rate, by linear
	 * interpolation, with every channel the same.
	 *
	 * @return 16-bit little-endian PCM with interleaved channels.
	 */
	public static byte[] speech(int sampleRate, int channels) {
		int frames = (int) ((long) SPEECH.length * sampleRate / 16000);
		ByteBuffer pcm = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN);

		for (int frame = 0; frame < frames; frame++) {
			double position = (double) frame * 16000 / sampleRate;
			int index = (int) position;
			double fraction = position - index;
			int next = Math.min(index + 1, SPEECH.length - 1);
			short sample = (short) Math.round(SPEECH[index] * (1 - fraction) + SPEECH[next] * fraction);

			for (int channel = 0; channel < channels; channel++) {
				pcm.putShort(sample);
			}
		}

		return pcm.array();
	}

	/**
	 * @return silence as 16-bit PCM with interleaved channels.
	 */
	public static byte[] silence(int millis, int sampleRate, int channels) {
		return new byte[(int) ((long) sampleRate * millis / 1000) * channels * 2];
	}

	/**
	 * @return the arrays one after another.
	 */
	public static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		for (byte[] part : parts) {
			bytes.writeBytes(part);
		}

		return bytes.toByteArray();
	}

	private static short[] load() {
		try (InputStream input = SpeechFixture.class.getResourceAsStream("/audio/vad-speech-16k.pcm")) {
			if (input == null) {
				throw new IllegalStateException("The speech fixture is missing");
			}

			ByteBuffer pcm = ByteBuffer.wrap(input.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
			short[] samples = new short[pcm.remaining() / 2];

			pcm.asShortBuffer().get(samples);

			return samples;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
