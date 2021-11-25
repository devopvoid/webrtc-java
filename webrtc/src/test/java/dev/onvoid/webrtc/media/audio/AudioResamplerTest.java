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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
	void notInitialized() {
		SampleBuffer buffer = new SampleBuffer(48000, 24000, 1);

		assertThrows(IllegalStateException.class, () -> {
			resampler.resample(buffer.src, buffer.nSamplesIn, buffer.dst);
		});
	}

	@Test
	void targetBufferUnderflow() {
		SampleBuffer buffer = new SampleBuffer(48000, 24000, 1);
		buffer.setTargetBufferSize(buffer.frameSizeOut / 2);

		reset(resampler, buffer);

		assertThrows(IllegalArgumentException.class, () -> {
			resample(resampler, buffer);
		});
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

		reset(resampler, buffer);

		int result = resample(resampler, buffer);

		assertEquals(buffer.nSamplesOut, result);
	}

	@Test
	void upSample() {
		SampleBuffer buffer = new SampleBuffer(32000, 48000, 1);

		reset(resampler, buffer);

		int result = resample(resampler, buffer);

		assertEquals(buffer.nSamplesOut, result);
	}

	private static void reset(AudioResampler resampler, SampleBuffer buffer) {
		resampler.reset(buffer.sampleRateIn, buffer.sampleRateOut, 1);
	}

	private static int resample(AudioResampler resampler, SampleBuffer buffer) {
		return resampler.resample(buffer.src, buffer.nSamplesIn, buffer.dst);
	}



	private static class SampleBuffer {

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

			nSamplesIn = sampleRateIn / 100 * channels; // 10 ms frame
			nSamplesOut = sampleRateOut / 100 * channels;
			frameSizeIn = nSamplesIn * bytesPerFrame;
			frameSizeOut = nSamplesOut * bytesPerFrame;

			src = new byte[frameSizeIn];
			dst = new byte[frameSizeOut];
		}

		void setTargetBufferSize(int size) {
			dst = new byte[size];
		}
	}
}
