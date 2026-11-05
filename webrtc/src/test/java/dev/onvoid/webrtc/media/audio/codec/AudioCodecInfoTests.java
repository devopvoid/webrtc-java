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

package dev.onvoid.webrtc.media.audio.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AudioCodecInfoTests {

	@Test
	void hashIgnoresTurkishLocale() {
		Locale locale = Locale.getDefault();

		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));

			AudioCodecInfo upper = codec("ISAC");
			AudioCodecInfo lower = codec("isac");

			assertEquals(upper, lower);
			assertEquals(upper.hashCode(), lower.hashCode());
			assertTrue(new HashSet<>(Set.of(upper)).contains(lower));
		}
		finally {
			Locale.setDefault(locale);
		}
	}

	/*
	 * Each pair is equal to equalsIgnoreCase, while lower or upper casing the
	 * whole name tells it apart even with Locale.ROOT.
	 */
	@Test
	void hashMatchesEquals() {
		String[][] pairs = {
				{ "I", "ı" },	// Dotless i.
				{ "i", "İ" },	// Capital I with a dot.
				{ "s", "ſ" },	// Long s.
				{ "k", "K" },	// Kelvin sign.
		};

		for (String[] pair : pairs) {
			AudioCodecInfo first = codec(pair[0]);
			AudioCodecInfo second = codec(pair[1]);

			assertEquals(first, second, pair[1]);
			assertEquals(first.hashCode(), second.hashCode(), pair[1]);
		}
	}

	/*
	 * Whether these two are equal depends on the Java version, code points are
	 * compared from Java 16 on, so that their hash codes have to match anyway.
	 */
	@Test
	void hashFoldsSupplementaryLetters() {
		assertEquals(codec("𐐀").hashCode(), codec("𐐨").hashCode());
	}

	@Test
	void hashCoversOtherFields() {
		AudioCodecInfo codec = codec("opus");

		assertNotEquals(codec.hashCode(), new AudioCodecInfo("opus", 16000, 2, Map.of()).hashCode());
		assertNotEquals(codec.hashCode(), new AudioCodecInfo("opus", 48000, 1, Map.of()).hashCode());
		assertNotEquals(codec.hashCode(), codec("g722").hashCode());
	}

	private static AudioCodecInfo codec(String name) {
		return new AudioCodecInfo(name, 48000, 2, Map.of());
	}
}
