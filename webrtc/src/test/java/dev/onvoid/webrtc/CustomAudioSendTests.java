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

package dev.onvoid.webrtc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The checks of {@code CustomAudioSource.pushAudio} reject what a sender drops.
 * These tests hold them to the other half of that: what passes reaches the
 * wire, also at the limits of what the checks let through.
 */
class CustomAudioSendTests extends TestBase {

	private static final long TIMEOUT_NANOS = 10_000_000_000L;


	@Test
	void acceptedFormatsAreSent() throws Exception {
		int[][] formats = { { 8000, 1 }, { 44100, 2 }, { 48000, 4 }, { 48000, 6 }, { 48000, 8 },
				{ 96000, 8 }, { 192000, 4 } };

		for (int[] format : formats) {
			try (TestMediaCall call = new TestMediaCall(factory, false, true)) {
				call.setAudioFormat(format[0], format[1]);
				call.negotiate();
				call.awaitConnected();
				call.startMedia();

				assertTrue(awaitPackets(call), format[0] + " Hz with " + format[1] + " channels was not sent");
			}
		}
	}

	private static boolean awaitPackets(TestMediaCall call) throws InterruptedException {
		long start = System.nanoTime();

		while (System.nanoTime() - start < TIMEOUT_NANOS) {
			Map<String, Object> stats = call.getOutboundAudioStats();

			if (stats != null && ((Number) stats.get("packetsSent")).longValue() > 0) {
				return true;
			}

			Thread.sleep(20);
		}

		return false;
	}

}
