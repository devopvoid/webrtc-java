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

package dev.onvoid.webrtc;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Each RTCStatsReport contains multiple RTCStats objects; one for each
 * underlying object (codec, stream, transport, etc.) that was inspected to
 * produce the stats.
 *
 * @author Alex Andres
 */
public class RTCStatsReport {

	private final Map<String, RTCStats> stats;

	private final long timestamp;


	protected RTCStatsReport(Map<String, RTCStats> stats, long timestamp) {
		this.stats = stats;
		this.timestamp = timestamp;
	}

	/**
	 * Returns the map of RTCStats objects.
	 *
	 * @return a map where the key is a string identifier and the value is an RTCStats object.
	 */
	public Map<String, RTCStats> getStats() {
		return stats;
	}

	/**
	 * Get the timestamp in microseconds.
	 *
	 * @return the timestamp in microseconds relative to the UNIX epoch.
	 */
	public long getTimestamp() {
		return timestamp;
	}

	@Override
	public String toString() {
		return String.format("%s@%d [stats=%s]",
				RTCStatsReport.class.getSimpleName(), hashCode(),
				stats.values().stream().map(Object::toString).collect(Collectors.joining(",\n")));
	}
}
