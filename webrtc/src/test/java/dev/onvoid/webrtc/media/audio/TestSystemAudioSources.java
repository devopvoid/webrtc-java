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

/**
 * Creates {@link SystemAudioSource}s whose platform capture is replaced, for
 * tests outside this package that need system audio without audio hardware.
 *
 * @author Alex Andres
 */
public final class TestSystemAudioSources {

	private TestSystemAudioSources() {
		// Static access only.
	}

	/**
	 * Creates a source whose capture delivers silence in real time, 10 ms of
	 * 48 kHz stereo every 10 ms, as a system output that plays nothing does.
	 *
	 * @return The source, not started.
	 */
	public static SystemAudioSource silent() {
		return new SystemAudioSource(SilentCapture::new);
	}



	private static final class SilentCapture implements SystemAudioSource.Backend {

		private static final long CHUNK_NANOS = 10_000_000L;

		private final byte[] chunk = new byte[480 * 2 * 2];

		private long next;


		@Override
		public void initialize() {
			next = System.nanoTime();
		}

		@Override
		public void pump(CustomAudioSource source) {
			while (System.nanoTime() >= next) {
				source.pushAudio(chunk, 16, 48000, 2, 480);

				next += CHUNK_NANOS;
			}
		}

		@Override
		public void dispose() {
		}

	}

}
