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

package dev.onvoid.webrtc.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.SyncClock;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;

import org.junit.jupiter.api.Test;

/**
 * Covers what a native extension library gets handed: the address of the
 * function table it calls, and the addresses of the media sources it feeds.
 *
 * @author Alex Andres
 */
class NativeApiTests extends TestBase {

	@Test
	void tableAddressIsStable() {
		long first = NativeApi.tableAddress();

		assertNotEquals(0L, first);
		// The table has static storage duration, so an extension may hold the
		// address for the lifetime of the process.
		assertEquals(first, NativeApi.tableAddress());
	}

	@Test
	void versionMatchesHeader() {
		// An extension refuses to run against a version it does not know, so
		// this must only ever change together with webrtc_java_api.h.
		assertEquals(1, NativeApi.version());
	}

	@Test
	void handleOfVideoSource() {
		CustomVideoSource source = new CustomVideoSource();

		assertNotEquals(0L, NativeApi.handleOf(source));

		source.dispose();

		// Disposal clears the handle, which is how an extension can tell that
		// the source it was given is gone.
		assertEquals(0L, NativeApi.handleOf(source));
	}

	@Test
	void handleOfAudioSource() {
		CustomAudioSource source = new CustomAudioSource();

		assertNotEquals(0L, NativeApi.handleOf(source));

		source.dispose();

		assertEquals(0L, NativeApi.handleOf(source));
	}

	@Test
	void handleOfNull() {
		assertThrows(NullPointerException.class, () -> NativeApi.handleOf(null));
	}

	@Test
	void mediaClockAdvances() throws Exception {
		// The clock the table's now_us() reports and in which the push
		// timestamps are interpreted.
		long first = SyncClock.currentTimeUs();

		Thread.sleep(20);

		long second = SyncClock.currentTimeUs();

		assertTrue(second > first, "Media clock did not advance");
		assertTrue(second - first >= 10_000,
				"Media clock advanced by " + (second - first) + " us over 20 ms");
	}

}
