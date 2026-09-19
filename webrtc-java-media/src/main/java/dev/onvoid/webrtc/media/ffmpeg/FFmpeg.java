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

package dev.onvoid.webrtc.media.ffmpeg;

import dev.onvoid.webrtc.internal.NativeLoader;

/**
 * Loads this module's native library and reports what it was built against.
 * <p>
 * The FFmpeg libraries are loaded first, in the order they depend on each
 * other, and under the names they were built with. A dynamic linker matches an
 * already loaded module by file name, so by the time the media library itself
 * is loaded every symbol it imports is resolved without changing any search
 * path.
 *
 * @author Alex Andres
 */
public final class FFmpeg {

	/** The name of this module's native library, without platform decoration. */
	private static final String LIBRARY = "webrtc-java-media";


	static {
		try {
			NativeLoader.loadLibrary(LIBRARY, dependencies());
		}
		catch (Exception e) {
			throw new RuntimeException("Load library '" + LIBRARY + "' failed", e);
		}
	}


	private FFmpeg() {
		// Static access only.
	}

	/**
	 * Returns the FFmpeg version the loaded native library was built against,
	 * for example {@code 7.1.1}. Useful to confirm which FFmpeg an application
	 * actually ended up with, since the libraries may be replaced.
	 *
	 * @return The FFmpeg version string.
	 */
	public static native String version();

	/**
	 * Returns the license of the loaded FFmpeg libraries, which must be an
	 * LGPL variant. A build that reports a GPL license is one this project did
	 * not produce, and redistributing it carries obligations this project does
	 * not meet.
	 *
	 * @return The FFmpeg license string, for example {@code LGPL version 2.1 or later}.
	 */
	public static native String license();

	/**
	 * Makes sure the native library is loaded. Loading happens when this class
	 * is first used, so this is only needed to bring the failure forward to a
	 * point where it can be reported.
	 */
	public static void load() {
		// Touching the class runs the static initializer.
	}

	/**
	 * Returns the shared libraries to load before this module's own, in the
	 * order they depend on each other, named as they are in the platform jar.
	 *
	 * @return The file names of the FFmpeg libraries for this platform.
	 */
	private static String[] dependencies() {
		String osName = System.getProperty("os.name").toLowerCase();

		if (osName.startsWith("windows")) {
			return new String[] {
					"avutil-59.dll",
					"swresample-5.dll",
					"swscale-8.dll",
					"avcodec-61.dll",
					"avformat-61.dll"
			};
		}
		if (osName.startsWith("mac os")) {
			return new String[] {
					"libavutil.59.dylib",
					"libswresample.5.dylib",
					"libswscale.8.dylib",
					"libavcodec.61.dylib",
					"libavformat.61.dylib"
			};
		}

		return new String[] {
				"libavutil.so.59",
				"libswresample.so.5",
				"libswscale.so.8",
				"libavcodec.so.61",
				"libavformat.so.61"
		};
	}

}
