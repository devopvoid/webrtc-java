/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.media.video.desktop;

import java.util.Objects;

/**
 * Represents a desktop source that can be captured, such as a screen or window.
 * Desktop sources are uniquely identified by their ID.
 *
 * @author Alex Andres
 */
public class DesktopSource {

	/** The title or name of the desktop source. */
	public final String title;

	/** The unique identifier of the desktop source. */
	public final long id;


	/**
	 * Creates a new desktop source with the specified title and ID.
	 *
	 * @param title The title or name of the desktop source.
	 * @param id    The unique identifier of the desktop source.
	 */
	public DesktopSource(String title, long id) {
		this.title = title;
		this.id = id;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}

		DesktopSource other = (DesktopSource) o;

		return id == other.id;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public String toString() {
		return String.format("%s@%d [title=%s, id=%s]",
				DesktopSource.class.getSimpleName(), hashCode(), title, id);
	}
}
