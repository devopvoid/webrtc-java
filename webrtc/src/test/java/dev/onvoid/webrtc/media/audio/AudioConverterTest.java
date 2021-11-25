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

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AudioConverterTest {

	@Test
	void downSample() {
		convert(new ProcessBuffer(48000, 44100, 1, 1));
	}

	@Test
	void upSample() {
		convert(new ProcessBuffer(44100, 48000, 1, 1));
	}

	@Test
	void downMix() {
		convert(new ProcessBuffer(32000, 32000, 2, 1));
	}

	@Test
	void upMix() {
		convert(new ProcessBuffer(32000, 32000, 1, 2));
	}

	@Test
	void downSampleDownMix() {
		convert(new ProcessBuffer(48000, 44100, 2, 1));
	}

	@Test
	void downSampleUpMix() {
		convert(new ProcessBuffer(48000, 44100, 1, 2));
	}

	@Test
	void upSampleDownMix() {
		convert(new ProcessBuffer(44100, 48000, 2, 1));
	}

	@Test
	void upSampleUpMix() {
		convert(new ProcessBuffer(44100, 48000, 1, 2));
	}

	@Test
	void targetBufferUnderflow() {
		ProcessBuffer buffer = new ProcessBuffer(48000, 24000, 2, 2);
		buffer.setTargetBufferSize(buffer.frameSizeOut / 2);

		assertThrows(IllegalArgumentException.class, () -> {
			convert(buffer);
		});
	}

	private static void convert(ProcessBuffer buffer) {
		AudioConverter converter = new AudioConverter(buffer.sampleRateIn,
				buffer.channelsIn, buffer.sampleRateOut, buffer.channelsOut);
		converter.convert(buffer.src, buffer.dst);
		converter.dispose();
	}



	private static class ProcessBuffer {

		final int bytesPerFrame = 2;

		final int channelsIn;
		final int channelsOut;

		final int sampleRateIn;
		final int sampleRateOut;

		final int nSamplesIn;
		final int nSamplesOut;

		final int frameSizeIn;
		final int frameSizeOut;

		byte[] src;
		byte[] dst;

		AudioProcessingStreamConfig streamConfigIn;
		AudioProcessingStreamConfig streamConfigOut;


		ProcessBuffer(int sampleRateIn, int sampleRateOut, int channelsIn, int channelsOut) {
			this.sampleRateIn = sampleRateIn;
			this.sampleRateOut = sampleRateOut;
			this.channelsIn = channelsIn;
			this.channelsOut = channelsOut;

			nSamplesIn = sampleRateIn / 100; // 10 ms frame
			nSamplesOut = sampleRateOut / 100;
			frameSizeIn = nSamplesIn * channelsIn;
			frameSizeOut = Math.max(nSamplesIn, nSamplesOut) * channelsOut;

			src = new byte[frameSizeIn * bytesPerFrame];
			dst = new byte[frameSizeOut * bytesPerFrame];

			streamConfigIn = new AudioProcessingStreamConfig(sampleRateIn, channelsIn);
			streamConfigOut = new AudioProcessingStreamConfig(sampleRateOut, channelsOut);
		}

		void setTargetBufferSize(int size) {
			dst = new byte[size];
		}
	}
}
