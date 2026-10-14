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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Selects built-in codecs by name. The native factories filter and order the
 * same way, so that what Java reports is what WebRTC offers.
 */
final class AudioCodecSelection {

	private AudioCodecSelection() {
	}

	/**
	 * Validates the codec names and returns them in canonical form: as the
	 * built-in codecs spell them, without duplicates, in the given order.
	 *
	 * @param builtin The built-in codecs.
	 * @param names   The names to select, compared ignoring case.
	 *
	 * @return The canonical names, empty to select all.
	 */
	static String[] canonicalNames(List<AudioCodecInfo> builtin, String... names) {
		Objects.requireNonNull(names, "Codec names are null");

		Set<String> canonical = new LinkedHashSet<>();

		for (String name : names) {
			Objects.requireNonNull(name, "Codec name is null");

			String match = null;

			for (AudioCodecInfo codec : builtin) {
				if (codec.getName().equalsIgnoreCase(name)) {
					match = codec.getName();
					break;
				}
			}

			if (match == null) {
				throw new IllegalArgumentException(String.format(
						"Unknown audio codec '%s', the built-in codecs are %s",
						name, builtinNames(builtin)));
			}

			canonical.add(match);
		}

		return canonical.toArray(new String[0]);
	}

	/**
	 * Returns the built-in codecs with the given names, ordered by the names
	 * and, for one name, by their built-in order.
	 *
	 * @param builtin The built-in codecs.
	 * @param names   Canonical names, empty to select all.
	 *
	 * @return The selected codecs, which cannot be modified.
	 */
	static List<AudioCodecInfo> select(List<AudioCodecInfo> builtin, String[] names) {
		if (names.length == 0) {
			return Collections.unmodifiableList(new ArrayList<>(builtin));
		}

		List<AudioCodecInfo> selected = new ArrayList<>();

		for (String name : names) {
			for (AudioCodecInfo codec : builtin) {
				if (codec.getName().equalsIgnoreCase(name)) {
					selected.add(codec);
				}
			}
		}

		return Collections.unmodifiableList(selected);
	}

	private static Set<String> builtinNames(List<AudioCodecInfo> builtin) {
		Set<String> names = new LinkedHashSet<>();

		for (AudioCodecInfo codec : builtin) {
			names.add(codec.getName());
		}

		return names;
	}
}
