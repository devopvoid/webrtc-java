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

import static org.junit.jupiter.api.Assumptions.abort;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * A file name that JNI and FFmpeg encode differently: characters of two,
 * three and four bytes in UTF-8, the last two beyond the Basic Multilingual
 * Plane, which the modified UTF-8 of JNI writes as surrogates.
 *
 * @author Alex Andres
 */
public final class UnicodeFileName {

	/** {@code ü}, {@code €}, an emoji and the G clef. */
	public static final String NAME = "clip-ü-€-😀-𝄞";


	private UnicodeFileName() {
		// Static access only.
	}

	/**
	 * Resolves the name with the given extension in the given directory, and
	 * skips the test where file names cannot hold it, as on Linux in the C
	 * locale.
	 *
	 * @param directory The directory of the file.
	 * @param extension The extension of the file, without the dot.
	 *
	 * @return The path of the file.
	 */
	public static Path resolve(Path directory, String extension) {
		try {
			return directory.resolve(NAME + "." + extension);
		}
		catch (InvalidPathException e) {
			return abort("File names cannot hold " + NAME + ": " + e.getMessage());
		}
	}

}
