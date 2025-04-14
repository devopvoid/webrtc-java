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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class VoiceActivityDetectorTest {

	private VoiceActivityDetector detector;


	@BeforeEach
	void setUp() {
		detector = new VoiceActivityDetector();
	}

	@AfterEach
	void tearDown() {
		if (detector != null) {
			detector.dispose();
		}
	}

	@Test
	void testInitialization() {
		assertNotNull(detector, "Detector should be initialized");
	}

	@Test
	void testProcessAndGetProbability() {
		// Create sample audio data (silence - all zeros).
		byte[] silenceData = new byte[320]; // 10ms at 16kHz mono with 16-bit samples
		int samplesPerChannel = 160;
		int sampleRate = 16000;

		// Process silent audio.
		detector.process(silenceData, samplesPerChannel, sampleRate);

		float probability = detector.getLastVoiceProbability();

		assertTrue(probability >= 0.0f && probability <= 1.0f,
				"Probability should be between 0.0 and 1.0");
	}

	@Test
	void testDispose() {
		// The First operation should work.
		detector.process(new byte[320], 160, 16000);

		// Dispose resources.
		detector.dispose();

		// After disposal, operations should either throw an exception or be safe to call.
		assertThrows(Exception.class, () -> {
			detector.process(new byte[320], 160, 16000);
		});

		detector = null;
	}

}
