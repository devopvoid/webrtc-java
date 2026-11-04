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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class AudioSourceTests {

	/**
	 * What an AudioSource throws on the module's playout thread goes to the
	 * uncaught exception handler, and the thread goes on pulling: the
	 * exception used to stay pending on that thread, for the JNI calls of all
	 * the pulls after it.
	 */
	@Test
	void throwingSourceIsReported() throws Exception {
		RuntimeException failure = new RuntimeException("source failed");
		CountDownLatch reported = new CountDownLatch(1);
		CountDownLatch pulledAgain = new CountDownLatch(1);
		AtomicInteger pulls = new AtomicInteger();

		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
			if (e == failure) {
				reported.countDown();
			}
			else if (previous != null) {
				previous.uncaughtException(thread, e);
			}
		});

		HeadlessAudioDeviceModule module = new HeadlessAudioDeviceModule();

		try {
			module.setAudioSource((samples, nSamples, nBytesPerSample, nChannels, samplesPerSec) -> {
				if (pulls.getAndIncrement() == 0) {
					throw failure;
				}

				pulledAgain.countDown();

				return nSamples;
			});

			module.initPlayout();
			module.startPlayout();

			assertTrue(reported.await(10, TimeUnit.SECONDS), "exception not reported");
			assertTrue(pulledAgain.await(10, TimeUnit.SECONDS), "source not pulled again");
		}
		finally {
			module.stopPlayout();
			module.dispose();

			Thread.setDefaultUncaughtExceptionHandler(previous);
		}
	}
}
