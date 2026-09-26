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

package dev.onvoid.webrtc.media.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests which FFmpeg the module actually loads.
 *
 * @author Alex Andres
 */
class FFmpegTest {

	@Test
	void loadsPinnedRelease() {
		// Set by the build from the version the submodule is pinned to.
		String expected = System.getProperty("ffmpeg.version");

		assertNotNull(expected, "ffmpeg.version is not set");

		// A release build names itself after its tag, n<version>.
		assertEquals("n" + expected, FFmpeg.version());
	}

	@Test
	void isLgpl() {
		// What the module ships under; a GPL or non-free build must not be
		// what ended up in the jar.
		assertTrue(FFmpeg.license().startsWith("LGPL"), FFmpeg.license());
	}

}
