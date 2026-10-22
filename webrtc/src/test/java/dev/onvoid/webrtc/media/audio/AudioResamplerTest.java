/*
 * Copyright 2021 Alex Andres
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AudioResamplerTest {

	private AudioResampler resampler;


	@BeforeEach
	void init() {
		resampler = new AudioResampler();
	}

	@AfterEach
	void dispose() {
		resampler.dispose();
	}

	@Test
	void targetBufferUnderflow() {
		SampleBuffer buffer = new SampleBuffer(48000, 24000, 1);
		buffer.setTargetBufferSize(buffer.frameSizeOut / 2);

		assertThrows(IllegalArgumentException.class, () -> resample(resampler, buffer));
	}

	@Test
	void stereoTargetUnderflow() {
		// Room for all samples of one channel, but not for both.
		SampleBuffer buffer = new SampleBuffer(48000, 48000, 2);
		buffer.setTargetBufferSize(buffer.frameSizeOut / 2);

		assertThrows(IllegalArgumentException.class, () -> resample(resampler, buffer));
	}

	@Test
	void sourceUnderflow() {
		SampleBuffer buffer = new SampleBuffer(48000, 16000, 2);
		buffer.src = new byte[buffer.frameSizeIn - 2];

		assertThrows(IllegalArgumentException.class, () -> resample(resampler, buffer));
	}

	@Test
	void invalidParameters() {
		SampleBuffer buffer = new SampleBuffer(48000, 16000, 1);

		assertThrows(IllegalArgumentException.class,
				() -> resampler.resample(buffer.src, buffer.nSamplesIn, buffer.dst, buffer.nSamplesOut, 0));
		assertThrows(IllegalArgumentException.class,
				() -> resampler.resample(buffer.src, buffer.nSamplesIn, buffer.dst, buffer.nSamplesOut, 9));
		assertThrows(IllegalArgumentException.class,
				() -> resampler.resample(buffer.src, buffer.nSamplesIn, buffer.dst, 0, 1));
		assertThrows(IllegalArgumentException.class,
				() -> resampler.resample(buffer.src, -1, buffer.dst, buffer.nSamplesOut, 1));
		assertThrows(IllegalArgumentException.class, () -> new AudioResampler(48000, 16000, 0));
		assertThrows(IllegalArgumentException.class, () -> new AudioResampler(0, 16000, 1));
	}

	@Test
	void oversizedTarget() {
		// The resampler writes the requested samples, not as many as the array holds.
		SampleBuffer buffer = new SampleBuffer(48000, 16000, 1);
		buffer.setTargetBufferSize(buffer.frameSizeOut * 4);

		assertEquals(buffer.nSamplesOut, resample(resampler, buffer));
		assertUntouchedAfter(buffer.dst, buffer.frameSizeOut);
	}

	@Test
	void stereo() {
		SampleBuffer buffer = new SampleBuffer(48000, 16000, 2);
		buffer.setTargetBufferSize(buffer.frameSizeOut * 2);

		// A loud left and a silent right channel.
		for (int i = 0; i < buffer.nSamplesIn; i++) {
			buffer.src[i * 4 + 1] = 0x40;
		}

		int result = 0;

		// The resampler has a delay, so the signal shows only after a few frames.
		for (int i = 0; i < 5; i++) {
			result = resample(resampler, buffer);
		}

		assertEquals(buffer.nSamplesOut, result);
		assertUntouchedAfter(buffer.dst, buffer.frameSizeOut);

		int last = buffer.nSamplesOut - 1;

		assertNotEquals(0, sample(buffer.dst, last * 2));
		assertEquals(0, sample(buffer.dst, last * 2 + 1));
	}

	@Test
	void constructorParameters() {
		SampleBuffer buffer = new SampleBuffer(48000, 44100, 2);
		AudioResampler resampler = new AudioResampler(48000, 44100, 2);

		int result = resample(resampler, buffer);

		resampler.dispose();

		assertEquals(buffer.nSamplesOut, result);
	}

	@Test
	void downSample() {
		SampleBuffer buffer = new SampleBuffer(48000, 44100, 1);

		int result = resample(resampler, buffer);

		assertEquals(buffer.nSamplesOut, result);
	}

	@Test
	void upSample() {
		SampleBuffer buffer = new SampleBuffer(32000, 48000, 1);

		int result = resample(resampler, buffer);

		assertEquals(buffer.nSamplesOut, result);
	}

	private static int resample(AudioResampler resampler, SampleBuffer buffer) {
		return resampler.resample(buffer.src, buffer.nSamplesIn, buffer.dst, buffer.nSamplesOut, buffer.channels);
	}

	private static short sample(byte[] samples, int index) {
		// Little-endian 16-bit PCM.
		return (short) ((samples[index * 2] & 0xFF) | (samples[index * 2 + 1] << 8));
	}

	private static void assertUntouchedAfter(byte[] samples, int length) {
		for (int i = length; i < samples.length; i++) {
			assertEquals(SampleBuffer.UNTOUCHED, samples[i], "Byte " + i + " was written");
		}
	}


	private static class SampleBuffer {

		static final byte UNTOUCHED = 0x55;

		final int bytesPerFrame = 2;

		final int channels;

		final int sampleRateIn;
		final int sampleRateOut;

		final int nSamplesIn;
		final int nSamplesOut;

		final int frameSizeIn;
		final int frameSizeOut;

		byte[] src;
		byte[] dst;


		SampleBuffer(int sampleRateIn, int sampleRateOut, int channels) {
			this.channels = channels;
			this.sampleRateIn = sampleRateIn;
			this.sampleRateOut = sampleRateOut;

			nSamplesIn = AudioResampler.getSamplesPerChannel(sampleRateIn); // 10 ms frame
			nSamplesOut = AudioResampler.getSamplesPerChannel(sampleRateOut);
			frameSizeIn = nSamplesIn * channels * bytesPerFrame;
			frameSizeOut = nSamplesOut * channels * bytesPerFrame;

			src = new byte[frameSizeIn];
			dst = new byte[frameSizeOut];
		}

		void setTargetBufferSize(int size) {
			dst = new byte[size];

			Arrays.fill(dst, UNTOUCHED);
		}
	}
}
