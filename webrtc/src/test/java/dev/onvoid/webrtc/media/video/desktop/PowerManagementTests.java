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

package dev.onvoid.webrtc.media.video.desktop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.onvoid.webrtc.TestBase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PowerManagementTests extends TestBase {

	private int released;


	@BeforeEach
	void resetReleased() {
		released = 0;
	}

	@Test
	void nativeCallsSucceed() {
		PowerManagement powerManagement = new PowerManagement();

		// Repeated on purpose, what the assertion does depends on the system running the test.
		assertDoesNotThrow(() -> {
			powerManagement.enableUserActivity();
			powerManagement.enableUserActivity();
			powerManagement.disableUserActivity();
			powerManagement.disableUserActivity();
		});
	}

	@Test
	void repeatedEnableNeedsOneDisable() {
		PowerManagement powerManagement = new RecordingPowerManagement();

		powerManagement.enableUserActivity();
		powerManagement.enableUserActivity();
		powerManagement.disableUserActivity();

		assertEquals(1, released);
	}

	@Test
	void lastInstanceReleases() {
		PowerManagement first = new RecordingPowerManagement();
		PowerManagement second = new RecordingPowerManagement();

		first.enableUserActivity();
		second.enableUserActivity();
		first.disableUserActivity();

		assertEquals(0, released);

		second.disableUserActivity();

		assertEquals(1, released);
	}

	@Test
	void repeatedDisableKeepsOtherRequest() {
		PowerManagement first = new RecordingPowerManagement();
		PowerManagement second = new RecordingPowerManagement();

		first.enableUserActivity();
		second.enableUserActivity();
		first.disableUserActivity();
		first.disableUserActivity();

		assertEquals(0, released);

		second.disableUserActivity();

		assertEquals(1, released);
	}

	@Test
	void disabledInstanceKeepsOtherRequest() {
		PowerManagement enabled = new RecordingPowerManagement();
		PowerManagement disabled = new RecordingPowerManagement();

		enabled.enableUserActivity();
		disabled.disableUserActivity();

		assertEquals(0, released);

		enabled.disableUserActivity();

		assertEquals(1, released);
	}

	@Test
	void enableAfterRelease() {
		PowerManagement powerManagement = new RecordingPowerManagement();

		powerManagement.enableUserActivity();
		powerManagement.disableUserActivity();
		powerManagement.enableUserActivity();
		powerManagement.disableUserActivity();

		assertEquals(2, released);
	}


	/**
	 * Keeps the system assertion out of these tests. The request state is process-wide, so each test
	 * disables all instances it enabled.
	 */
	private class RecordingPowerManagement extends PowerManagement {

		@Override
		void enableUserActivityInternal() {
		}

		@Override
		void disableUserActivityInternal() {
			released++;
		}
	}
}
