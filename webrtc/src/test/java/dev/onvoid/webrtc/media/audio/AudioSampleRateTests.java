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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AudioSampleRateTests {

	@Test
	void rejectsFractionalFrames() {
		for (int rate : new int[] { 101, 44101, 48001, 48099, 383999 }) {
			assertThrows(IllegalArgumentException.class, () -> new AudioConverter(rate, 1, 48000, 1));
			assertThrows(IllegalArgumentException.class, () -> new AudioConverter(48000, 1, rate, 1));
			assertThrows(IllegalArgumentException.class, () -> new AudioResampler(rate, 48000, 1));
			assertThrows(IllegalArgumentException.class, () -> new AudioResampler(48000, rate, 1));
			assertThrows(IllegalArgumentException.class, () -> AudioResampler.getSamplesPerChannel(rate));
			assertThrows(IllegalArgumentException.class, () -> new AudioProcessingStreamConfig(rate, 1));
		}
	}

	@Test
	void acceptsIntegralFrames() {
		for (int rate : new int[] { 100, 8000, 44100, 48000, 384000 }) {
			assertEquals(rate / 100, AudioResampler.getSamplesPerChannel(rate));
			assertEquals(rate, new AudioProcessingStreamConfig(rate, 1).sampleRate);
			AudioConverter converter = new AudioConverter(rate, 1, rate, 1);
			try {
				assertEquals(rate / 100 * 2, converter.getTargetBufferSize());
			}
			finally {
				converter.dispose();
			}
		}
	}
}
