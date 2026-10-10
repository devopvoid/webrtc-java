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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An audio codec as an audio codec factory offers it in SDP: its name, clock
 * rate, number of channels and format parameters, for example "opus" at 48000
 * Hz with 2 channels and {@code minptime=10;useinbandfec=1}.
 *
 * @author Alex Andres
 */
public final class AudioCodecInfo {

	private final String name;

	private final int clockRate;

	private final int channels;

	private final Map<String, String> parameters;


	/*
	 * Created by the native api.
	 */
	AudioCodecInfo(String name, int clockRate, int channels,
			Map<String, String> parameters) {
		this.name = name;
		this.clockRate = clockRate;
		this.channels = channels;
		this.parameters = Collections.unmodifiableMap(new HashMap<>(parameters));
	}

	/**
	 * Returns the codec name, e.g. "opus", "G722", "PCMU" or "PCMA".
	 *
	 * @return The codec name.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Returns the RTP clock rate in Hz.
	 *
	 * @return The clock rate.
	 */
	public int getClockRate() {
		return clockRate;
	}

	/**
	 * Returns the number of channels as signaled in SDP.
	 *
	 * @return The number of channels.
	 */
	public int getChannels() {
		return channels;
	}

	/**
	 * Returns the format parameters, the "a=fmtp" line of the codec.
	 *
	 * @return The format parameters, which cannot be modified.
	 */
	public Map<String, String> getParameters() {
		return parameters;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof AudioCodecInfo)) {
			return false;
		}

		AudioCodecInfo other = (AudioCodecInfo) obj;

		return clockRate == other.clockRate && channels == other.channels
				&& name.equalsIgnoreCase(other.name)
				&& parameters.equals(other.parameters);
	}

	@Override
	public int hashCode() {
		return Objects.hash(name.toLowerCase(), clockRate, channels, parameters);
	}

	@Override
	public String toString() {
		return String.format("%s/%d/%d %s", name, clockRate, channels, parameters);
	}
}
