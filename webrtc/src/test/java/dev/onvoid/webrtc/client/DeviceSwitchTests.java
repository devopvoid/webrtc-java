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

package dev.onvoid.webrtc.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Drives {@link DevicesImpl#switchDevice} with steps that record what ran, as
 * a module that refuses a switch while running, or has lost the device, would
 * answer them.
 */
class DeviceSwitchTests {

	private final List<String> steps = new ArrayList<>();


	@Test
	void switchesWhileRunning() {
		DevicesImpl.switchDevice(() -> steps.add("select"), stop(), start());

		assertEquals(Arrays.asList("select"), steps);
	}

	@Test
	void retriesRefusedSwitchStopped() {
		DevicesImpl.switchDevice(select(new Error("busy"), null), stop(), start());

		assertEquals(Arrays.asList("select", "stop", "select", "start"), steps);
	}

	@Test
	void missingDeviceIsNotRetried() {
		assertThrows(IllegalArgumentException.class, () -> DevicesImpl.switchDevice(
				select(new IllegalArgumentException("gone"), null), stop(), start()));

		// Still running on the device it had.
		assertEquals(Arrays.asList("select"), steps);
	}

	@Test
	void deviceLostBeforeRetryRestarts() {
		// Unplugged between the refused switch and the second attempt.
		assertThrows(IllegalArgumentException.class, () -> DevicesImpl.switchDevice(
				select(new Error("busy"), new IllegalArgumentException("gone")),
				stop(), start()));

		assertEquals(Arrays.asList("select", "stop", "select", "start"), steps);
	}

	/**
	 * Selects by throwing the given errors, one per attempt, and succeeding
	 * once they run out or the next one is null.
	 */
	private Runnable select(Throwable... errors) {
		int[] attempt = { 0 };

		return () -> {
			steps.add("select");

			Throwable error = attempt[0] < errors.length ? errors[attempt[0]] : null;

			attempt[0]++;

			if (error instanceof RuntimeException) {
				throw (RuntimeException) error;
			}
			if (error instanceof Error) {
				throw (Error) error;
			}
		};
	}

	private Runnable stop() {
		return () -> steps.add("stop");
	}

	private Runnable start() {
		return () -> steps.add("start");
	}
}
