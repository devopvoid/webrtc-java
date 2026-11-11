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

import java.util.Locale;

/**
 * The FFmpeg libraries that the native library of this module needs, named
 * as they are in the platform jar. Kept apart from {@link FFmpeg}, whose
 * initialization loads them: a GraalVM native image carries those that its
 * metadata names, and a test compares that metadata with these names without
 * loading anything.
 *
 * @author Alex Andres
 */
final class FFmpegLibraries {

	private FFmpegLibraries() {
		// Static access only.
	}

	/**
	 * Returns the shared libraries to load before this module's own on the
	 * given operating system, in the order they depend on each other.
	 *
	 * @param osName The name of the operating system, as the system property
	 *               {@code os.name} gives it.
	 *
	 * @return The file names of the FFmpeg libraries for that system.
	 */
	static String[] forSystem(String osName) {
		osName = osName.toLowerCase(Locale.ROOT);

		if (osName.startsWith("windows")) {
			return new String[] {
					"avutil-60.dll",
					"swresample-6.dll",
					"swscale-9.dll",
					"avcodec-62.dll",
					"avformat-62.dll"
			};
		}
		if (osName.startsWith("mac os")) {
			return new String[] {
					"libavutil.60.dylib",
					"libswresample.6.dylib",
					"libswscale.9.dylib",
					"libavcodec.62.dylib",
					"libavformat.62.dylib"
			};
		}

		return new String[] {
				"libavutil.so.60",
				"libswresample.so.6",
				"libswscale.so.9",
				"libavcodec.so.62",
				"libavformat.so.62"
		};
	}

}
