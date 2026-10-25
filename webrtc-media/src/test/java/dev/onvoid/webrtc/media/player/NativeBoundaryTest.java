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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.onvoid.webrtc.media.video.CustomVideoSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Tests that a C++ exception in the native library reaches Java as a Java
 * exception rather than ending the process, with nothing it held left behind.
 */
@Execution(ExecutionMode.SAME_THREAD)
class NativeBoundaryTest {

	private static final String ASSET = "/media-test.webm";


	@AfterEach
	void disarm() {
		AllocationFault.disarm();
	}

	@Test
	void translatesNativeExceptions() {
		OutOfMemoryError memory = assertThrows(OutOfMemoryError.class,
				() -> AllocationFault.throwInNative(0, "unused"));
		assertEquals("Native memory allocation failed", memory.getMessage());

		// The text of a standard exception is in no defined encoding, so only
		// its ASCII reaches Java.
		RuntimeException runtime = assertThrows(RuntimeException.class,
				() -> AllocationFault.throwInNative(1, "Fehler: ü"));
		assertEquals("Fehler: ??", runtime.getMessage());

		// The exception of the JNI call that failed is the one Java sees.
		assertThrows(NoClassDefFoundError.class, () -> AllocationFault.throwInNative(2, "unused"));

		RuntimeException unknown = assertThrows(RuntimeException.class,
				() -> AllocationFault.throwInNative(3, "unused"));
		assertEquals("Unknown native exception", unknown.getMessage());

		assertEquals(0, AllocationFault.heldStrings());
	}

	@Test
	void playerFailingToStartReleasesReader() throws Exception {
		Path asset = MediaReaderTest.asset(ASSET);
		MediaReader reader = new MediaReader(asset);
		CustomVideoSource videoSource = new CustomVideoSource();
		CustomAudioSource audioSource = new CustomAudioSource();

		try {
			AllocationFault.failNextThreadStart();

			RuntimeException e = assertThrows(RuntimeException.class,
					() -> new MediaPlayer(reader, videoSource, audioSource));
			assertTrue(e.getMessage().startsWith("Starting a thread failed"), e.getMessage());

			// Taken over by the player, and released along with it.
			assertThrows(IllegalStateException.class, reader::getInfo);

			try (MediaPlayer player = new MediaPlayer(new MediaReader(asset),
					videoSource, audioSource)) {
				assertEquals(MediaPlayerState.IDLE, player.getState());
			}
		}
		finally {
			reader.close();
			videoSource.dispose();
			audioSource.dispose();
		}
	}

}
