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

package dev.onvoid.webrtc.media.video.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class VideoCodecInfoTests {

	/*
	 * Each pair is equal to equalsIgnoreCase, while upper casing the whole name
	 * tells it apart even with Locale.ROOT.
	 */
	@Test
	void hashMatchesEquals() {
		String[][] pairs = {
				{ "i", "İ" },	// Capital I with a dot.
				{ "k", "K" },	// Kelvin sign.
		};

		for (String[] pair : pairs) {
			VideoCodecInfo first = new VideoCodecInfo(pair[0]);
			VideoCodecInfo second = new VideoCodecInfo(pair[1]);

			assertEquals(first, second, pair[1]);
			assertEquals(first.hashCode(), second.hashCode(), pair[1]);
		}
	}

	@Test
	void hashIgnoresNameCase() {
		assertEquals(new VideoCodecInfo("vp8").hashCode(), new VideoCodecInfo("VP8").hashCode());
	}

	@Test
	void hashCoversOtherFields() {
		VideoCodecInfo codec = new VideoCodecInfo("VP9");

		assertNotEquals(codec.hashCode(), new VideoCodecInfo("VP8").hashCode());
		assertNotEquals(codec.hashCode(), new VideoCodecInfo("VP9", Map.of("a", "b")).hashCode());
		assertNotEquals(codec.hashCode(), new VideoCodecInfo("VP9", Map.of(), List.of("L1T3")).hashCode());
	}
}
