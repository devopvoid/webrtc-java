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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class AudioConverterTest {

	private static final byte UNTOUCHED = 0x55;

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

	@Test
	void stereoDownSample() {
		// 320 bytes per output frame, the converter used to write 640.
		assertBounded(16000, 2, 8000, 2);
	}

	@Test
	void stereoUpSample() {
		assertBounded(8000, 2, 16000, 2);
	}

	@Test
	void downMixResampleStereo() {
		// Remixes to stereo, then resamples stereo.
		assertBounded(16000, 4, 8000, 2);
	}

	@Test
	void resampleStereoUpMix() {
		// Resamples stereo, then remixes it.
		assertBounded(8000, 2, 16000, 4);
	}

	@Test
	void stereoKeepsChannels() {
		AudioConverter converter = new AudioConverter(48000, 2, 16000, 2);
		byte[] src = new byte[480 * 2 * 2];
		byte[] dst = new byte[converter.getTargetBufferSize()];

		// A loud left and a silent right channel.
		for (int i = 0; i < 480; i++) {
			src[i * 4 + 1] = 0x40;
		}

		// The resampler has a delay, so the signal shows only after a few frames.
		for (int i = 0; i < 5; i++) {
			converter.convert(src, dst);
		}

		converter.dispose();

		int last = 160 - 1;

		assertNotEquals(0, sample(dst, last * 2));
		assertEquals(0, sample(dst, last * 2 + 1));
	}

	@Test
	void invalidFormats() {
		assertThrows(IllegalArgumentException.class, () -> new AudioConverter(0, 1, 16000, 1));
		assertThrows(IllegalArgumentException.class, () -> new AudioConverter(48000, 1, 400000, 1));
		assertThrows(IllegalArgumentException.class, () -> new AudioConverter(48000, 0, 16000, 1));
		assertThrows(IllegalArgumentException.class, () -> new AudioConverter(48000, 1, 16000, 9));

		// 1920 samples of 8 channels exceed the frame the channels are remixed in.
		assertThrows(IllegalArgumentException.class, () -> new AudioConverter(192000, 8, 192000, 1));
	}

	@Test
	void nativeBoundaryRejectsInvalidSizes() throws Exception {
		// Bypass Java validation to exercise the checks protecting the native buffers.
		Method convert = AudioConverter.class.getDeclaredMethod("convertInternal",
				byte[].class, int.class, byte[].class, int.class);
		convert.setAccessible(true);

		AudioConverter converter = new AudioConverter(16000, 2, 8000, 2);
		int srcSamples = 160 * 2;
		int dstSamples = 80 * 2;
		byte[] src = new byte[srcSamples * 2];
		byte[] dst = new byte[dstSamples * 2];

		try {
			Object[][] invalid = {
				{ src, srcSamples - 1, dst, dstSamples },
				{ new byte[src.length + 2], srcSamples + 1, dst, dstSamples },
				{ src, srcSamples, dst, dstSamples - 1 },
				{ new byte[src.length - 2], srcSamples, dst, dstSamples },
				{ src, srcSamples, new byte[dst.length - 2], dstSamples },
				{ src, -1, dst, dstSamples },
				{ src, srcSamples, dst, -1 },
			};

			for (Object[] args : invalid) {
				InvocationTargetException error = assertThrows(InvocationTargetException.class,
						() -> convert.invoke(converter, args));
				assertTrue(error.getCause() instanceof IllegalArgumentException, String.valueOf(error.getCause()));
			}

			InvocationTargetException nullError = assertThrows(InvocationTargetException.class,
					() -> convert.invoke(converter, null, srcSamples, dst, dstSamples));
			assertTrue(nullError.getCause() instanceof NullPointerException);
		}
		finally {
			converter.dispose();
		}
	}

	private static void assertBounded(int srcRate, int srcChannels, int dstRate, int dstChannels) {
		AudioConverter converter = new AudioConverter(srcRate, srcChannels, dstRate, dstChannels);
		int frameSizeOut = dstRate / 100 * dstChannels * 2;

		byte[] src = new byte[srcRate / 100 * srcChannels * 2];
		// As many guard bytes behind the frame as the frame has.
		byte[] dst = new byte[frameSizeOut * 2];

		Arrays.fill(dst, UNTOUCHED);

		int result = converter.convert(src, dst);

		converter.dispose();

		assertEquals(frameSizeOut, converter.getTargetBufferSize());
		assertEquals(frameSizeOut / 2, result);

		for (int i = frameSizeOut; i < dst.length; i++) {
			assertEquals(UNTOUCHED, dst[i], "Byte " + i + " was written");
		}
	}

	private static short sample(byte[] samples, int index) {
		// Little-endian 16-bit PCM.
		return (short) ((samples[index * 2] & 0xFF) | (samples[index * 2 + 1] << 8));
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
