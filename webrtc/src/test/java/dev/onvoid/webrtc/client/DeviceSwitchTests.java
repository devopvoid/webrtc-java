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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

	@Test
	void retryAndRestartFail() {
		// The second selection fails, and so does the restart. Thrown is why
		// the switch failed; the restart and the refusal are kept in it.
		Error refused = new Error("busy");
		Error failed = new Error("still busy");
		Error restart = new Error("restart failed");

		Error e = assertThrows(Error.class, () -> DevicesImpl.switchDevice(
				select(refused, failed), stop(), start(restart)));

		assertSame(failed, e);
		assertArrayEquals(new Throwable[] { restart, refused }, e.getSuppressed());
		assertEquals(Arrays.asList("select", "stop", "select", "start"), steps);
	}

	@Test
	void restartFailureKeepsRefusal() {
		Error refused = new Error("busy");
		IllegalStateException restart = new IllegalStateException("restart failed");

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> DevicesImpl.switchDevice(
				select(refused, null), stop(), start(restart)));

		assertSame(restart, e);
		assertArrayEquals(new Throwable[] { refused }, e.getSuppressed());
	}

	@Test
	void failedStopStillRestarts() {
		// Not stopped, so not selected again, but started as best it can.
		Error refused = new Error("busy");
		IllegalStateException stopped = new IllegalStateException("stop failed");

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> DevicesImpl.switchDevice(
				select(refused, null), () -> {
					steps.add("stop");
					throw stopped;
				}, start()));

		assertSame(stopped, e);
		assertArrayEquals(new Throwable[] { refused }, e.getSuppressed());
		assertEquals(Arrays.asList("select", "stop", "start"), steps);
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

	private Runnable start(RuntimeException failure) {
		return () -> {
			steps.add("start");
			throw failure;
		};
	}

	private Runnable start(Error failure) {
		return () -> {
			steps.add("start");
			throw failure;
		};
	}
}
