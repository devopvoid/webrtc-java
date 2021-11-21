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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.audio.AudioProcessingConfig.NoiseSuppression;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AudioProcessingTest extends TestBase {

	private AudioProcessing audioProcessing;


	@BeforeEach
	void init() {
		audioProcessing = new AudioProcessing();
	}

	@AfterEach
	void dispose() {
		audioProcessing.dispose();
	}

	@Test
	void applyConfig() {
		AudioProcessingConfig config = new AudioProcessingConfig();
		config.echoCanceller.enabled = true;
		config.echoCanceller.enforceHighPassFiltering = true;

		config.gainControl.enabled = true;

		config.highPassFilter.enabled = true;

		config.noiseSuppression.enabled = true;
		config.noiseSuppression.level = NoiseSuppression.Level.HIGH;

		config.residualEchoDetector.enabled = true;
		config.transientSuppression.enabled = true;
		config.levelEstimation.enabled = true;
		config.voiceDetection.enabled = true;

		audioProcessing.applyConfig(config);
	}

	@Test
	void getStats() {
		assertNotNull(audioProcessing.getStatistics());
	}

	@Test
	void processByteStream() {
		ProcessBuffer buffer = new ProcessBuffer(48000, 48000, 1);

		assertEquals(0, process(audioProcessing, buffer));
	}

	@Test
	void processReverseStream() {
		ProcessBuffer buffer = new ProcessBuffer(48000, 48000, 1);

		assertEquals(0, processReverse(audioProcessing, buffer));
	}

	@Test
	void streamDelay() {
		int delay = 70;

		audioProcessing.setStreamDelayMs(delay);

		assertEquals(delay, audioProcessing.getStreamDelayMs());
	}

	private static int process(AudioProcessing audioProcessing, ProcessBuffer buffer) {
		return audioProcessing.processStream(buffer.src, buffer.streamConfigIn,
				buffer.streamConfigOut, buffer.dst);
	}

	private static int processReverse(AudioProcessing audioProcessing, ProcessBuffer buffer) {
		return audioProcessing.processReverseStream(buffer.src, buffer.streamConfigIn,
				buffer.streamConfigOut, buffer.dst);
	}



	private static class ProcessBuffer {

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

		AudioProcessingStreamConfig streamConfigIn;
		AudioProcessingStreamConfig streamConfigOut;


		ProcessBuffer(int sampleRateIn, int sampleRateOut, int channels) {
			this.channels = channels;
			this.sampleRateIn = sampleRateIn;
			this.sampleRateOut = sampleRateOut;

			nSamplesIn = sampleRateIn / 100 * channels; // 10 ms frame
			nSamplesOut = sampleRateOut / 100 * channels;
			frameSizeIn = nSamplesIn * bytesPerFrame;
			frameSizeOut = nSamplesOut * bytesPerFrame;

			src = new byte[frameSizeIn];
			dst = new byte[frameSizeOut];

			streamConfigIn = new AudioProcessingStreamConfig(sampleRateIn, channels);
			streamConfigOut = new AudioProcessingStreamConfig(sampleRateOut, channels);
		}
	}
}
