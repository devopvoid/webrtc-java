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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.onvoid.webrtc.media.MediaDevices;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Plays out on the default speaker through the platform audio device module.
 * Needs a real playout device and is skipped without one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PlayoutIntegrationTest {

	@Test
	@EnabledOnOs(OS.WINDOWS)
	void playoutOnDefaultSpeaker() throws Exception {
		AudioDevice speaker = null;

		try {
			speaker = MediaDevices.getDefaultAudioRenderDevice();
		}
		catch (RuntimeException e) {
			// No audio system on this host.
		}

		assumeTrue(speaker != null, "no playout device");

		// WebRTC exchanges mono or stereo 10 ms blocks with the module, whatever
		// the device mixes in. A device whose shared-mode mix format has more
		// channels, as gaming headsets that mix in 7.1 have, accepts no stereo
		// stream, so the module opens it in its mix format and converts. Without
		// that, initPlayout() fails on such a device and no block arrives.
		CountDownLatch blocks = new CountDownLatch(20);
		AtomicReference<String> wrongFormat = new AtomicReference<>();

		AudioDeviceModule module = new AudioDeviceModule();

		try {
			module.setPlayoutDevice(speaker);
			module.setAudioSource(new AudioSource() {

				@Override
				public int onPlaybackData(byte[] audioSamples, int nSamples,
						int nBytesPerSample, int nChannels, int samplesPerSec) {
					// This runs on the module's render thread, where a thrown
					// assertion is lost and even printing fails under Surefire,
					// so the format is recorded and checked on the test thread.
					if (nChannels < 1 || nChannels > 2
							|| nBytesPerSample != 2 * nChannels
							|| nSamples * 100 != samplesPerSec) {
						wrongFormat.compareAndSet(null, String.format(
								"%d frames, %d bytes per frame, %d channels, %d Hz",
								nSamples, nBytesPerSample, nChannels,
								samplesPerSec));
					}

					blocks.countDown();

					// The buffer is silent; play it.
					return nSamples;
				}
			});

			module.initPlayout();
			module.startPlayout();

			try {
				assertTrue(blocks.await(5, TimeUnit.SECONDS),
						"playout did not run");
				assertNull(wrongFormat.get(), "unexpected playout format");
			}
			finally {
				module.stopPlayout();
			}
		}
		finally {
			module.dispose();
		}
	}
}
