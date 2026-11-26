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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests the jitter buffer target of a receiver.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RTCRtpReceiverJitterBufferTests extends TestBase {

	private static final long TIMEOUT_SECONDS = 15;

	/** The frames to wait for before the delay is measured. */
	private static final long FRAMES = 30;


	@Test
	void targetRaisesTargetDelay() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");

			try {
				// Set before media flows, so that every frame counted has it.
				receiver.setJitterBufferTarget(400.0);

				call.awaitConnected();
				call.startMedia();

				Map<String, Object> stats = awaitEmittedFrames(call);

				// Both are sums over the emitted frames. For video, WebRTC
				// counts the target in the target delay; its minimum delay is
				// what the jitter estimate asks for.
				double emitted = number(stats, "jitterBufferEmittedCount");
				double target = number(stats, "jitterBufferTargetDelay") / emitted;

				assertTrue(target >= 0.3, "The target delay per frame is " + target + " s");
			}
			finally {
				receiver.dispose();
			}
		}
	}

	@Test
	void targetOutsideRangeIsRejected() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");

			try {
				assertThrows(IllegalArgumentException.class, () -> receiver.setJitterBufferTarget(-1.0));
				assertThrows(IllegalArgumentException.class, () -> receiver.setJitterBufferTarget(4000.5));
				assertThrows(IllegalArgumentException.class, () -> receiver.setJitterBufferTarget(Double.NaN));

				assertDoesNotThrow(() -> receiver.setJitterBufferTarget(0.0));
				assertDoesNotThrow(() -> receiver.setJitterBufferTarget(RTCRtpReceiver.MAX_JITTER_BUFFER_TARGET));
				assertDoesNotThrow(() -> receiver.setJitterBufferTarget(null));
			}
			finally {
				receiver.dispose();
			}
		}
	}

	@Test
	void disposedReceiverIsRejected() throws Exception {
		try (TestMediaCall call = new TestMediaCall(factory, true, false)) {
			call.negotiate();

			RTCRtpReceiver receiver = call.getReceiver("video");
			receiver.dispose();

			assertThrows(IllegalStateException.class, () -> receiver.setJitterBufferTarget(100.0));
		}
	}

	private static Map<String, Object> awaitEmittedFrames(TestMediaCall call) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (true) {
			Map<String, Object> stats = call.getInboundVideoStats();

			if (stats != null && stats.get("jitterBufferEmittedCount") != null
					&& number(stats, "jitterBufferEmittedCount") >= FRAMES) {
				return stats;
			}
			if (System.nanoTime() > deadline) {
				fail("Fewer than " + FRAMES + " frames emitted: " + stats);
			}

			Thread.sleep(100);
		}
	}

	private static double number(Map<String, Object> stats, String name) {
		return ((Number) stats.get(name)).doubleValue();
	}

}
