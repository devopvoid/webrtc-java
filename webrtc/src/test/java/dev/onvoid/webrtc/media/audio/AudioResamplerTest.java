/*
 * Copyright 2019 Alex Andres
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

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AudioResamplerTest extends TestBase {

	private AudioResampler resampler;


	@AfterEach
	void dispose() {
		resampler.dispose();
	}

	@Test
	void targetBufferUnderflow() {
		resampler = new AudioResampler(48000, 24000);

		SampleBuffer buffer = new SampleBuffer(48000, 24000);
		buffer.setTargetBufferSize(buffer.frameSizeOut / 2);

		assertThrows(IllegalArgumentException.class, () -> {
			resample(resampler, buffer);
		});
	}

	@Test
	void downSample() {
		resampler = new AudioResampler(48000, 44100);

		SampleBuffer buffer = new SampleBuffer(48000, 44100);

		int result = resample(resampler, buffer);

		assertEquals(buffer.nSamplesOut, result);
	}

	@Test
	void upSample() {
		resampler = new AudioResampler(32000, 48000);

		SampleBuffer buffer = new SampleBuffer(32000, 48000);

		int result = resample(resampler, buffer);

		assertEquals(buffer.nSamplesOut, result);
	}

	private static int resample(AudioResampler resampler, SampleBuffer buffer) {
		return resampler.resample(buffer.src, buffer.nSamplesIn, buffer.dst);
	}



	private static class SampleBuffer {

		final int bytesPerFrame = 2;

		final int sampleRateIn;
		final int sampleRateOut;

		final int nSamplesIn;
		final int nSamplesOut;

		final int frameSizeIn;
		final int frameSizeOut;

		byte[] src;
		byte[] dst;


		SampleBuffer(int sampleRateIn, int sampleRateOut) {
			this.sampleRateIn = sampleRateIn;
			this.sampleRateOut = sampleRateOut;

			nSamplesIn = sampleRateIn / 100; // 10 ms frame
			nSamplesOut = sampleRateOut / 100;
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
