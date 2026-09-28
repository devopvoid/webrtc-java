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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Describes a video codec the way it is negotiated in SDP: its name, such as
 * "VP8" or "H264", and its format parameters, such as the H.264 profile. A
 * codec factory lists the formats it supports as VideoCodecInfos, and is asked
 * for an encoder or decoder for one of them.
 * <p>
 * A name WebRTC does not know, for example "X-MYCODEC", is negotiated like any
 * other, and its frames are sent with the generic RTP packetization.
 *
 * @author Alex Andres
 */
public final class VideoCodecInfo {

	private final String name;

	/** A HashMap, which is what the native side reads. */
	private final HashMap<String, String> parameters;

	private final String[] scalabilityModes;


	/**
	 * Creates a codec description without format parameters.
	 *
	 * @param name The codec name, such as "VP8".
	 */
	public VideoCodecInfo(String name) {
		this(name, Collections.emptyMap());
	}

	/**
	 * Creates a codec description.
	 *
	 * @param name       The codec name, such as "H264".
	 * @param parameters The SDP format parameters, such as {@code
	 *                   profile-level-id}.
	 */
	public VideoCodecInfo(String name, Map<String, String> parameters) {
		this(name, parameters, Collections.emptyList());
	}

	/**
	 * Creates a codec description.
	 *
	 * @param name             The codec name, such as "VP9".
	 * @param parameters       The SDP format parameters.
	 * @param scalabilityModes The scalability modes the codec supports, such
	 *                         as "L1T3", as named in the WebRTC SVC
	 *                         specification.
	 */
	public VideoCodecInfo(String name, Map<String, String> parameters,
			List<String> scalabilityModes) {
		Objects.requireNonNull(name, "Codec name is null");
		Objects.requireNonNull(parameters, "Codec parameters are null");
		Objects.requireNonNull(scalabilityModes, "Scalability modes are null");

		if (name.isEmpty()) {
			throw new IllegalArgumentException("Codec name is empty");
		}

		this.name = name;
		this.parameters = new HashMap<>(parameters);
		this.scalabilityModes = scalabilityModes.toArray(new String[0]);

		for (Map.Entry<String, String> entry : this.parameters.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				throw new IllegalArgumentException("Codec parameters must not contain null");
			}
		}
		for (String mode : this.scalabilityModes) {
			Objects.requireNonNull(mode, "Scalability mode is null");
		}
	}

	/**
	 * Created from native code.
	 */
	@SuppressWarnings("unused")
	private VideoCodecInfo(String name, Map<String, String> parameters,
			String[] scalabilityModes) {
		this(name, parameters, Arrays.asList(scalabilityModes));
	}

	/**
	 * @return The codec name, such as "VP8".
	 */
	public String getName() {
		return name;
	}

	/**
	 * @return The SDP format parameters, unmodifiable.
	 */
	public Map<String, String> getParameters() {
		return Collections.unmodifiableMap(parameters);
	}

	/**
	 * @return The scalability modes the codec supports, unmodifiable.
	 */
	public List<String> getScalabilityModes() {
		return Collections.unmodifiableList(Arrays.asList(scalabilityModes));
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null || getClass() != obj.getClass()) {
			return false;
		}

		VideoCodecInfo other = (VideoCodecInfo) obj;

		return name.equalsIgnoreCase(other.name)
				&& parameters.equals(other.parameters)
				&& Arrays.equals(scalabilityModes, other.scalabilityModes);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name.toUpperCase(java.util.Locale.ROOT), parameters,
				Arrays.hashCode(scalabilityModes));
	}

	@Override
	public String toString() {
		return String.format("%s@%d [name=%s, parameters=%s, scalabilityModes=%s]",
				VideoCodecInfo.class.getSimpleName(), hashCode(), name,
				parameters, Arrays.toString(scalabilityModes));
	}

}
