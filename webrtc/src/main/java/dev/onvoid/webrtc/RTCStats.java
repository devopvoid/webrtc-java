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

/**
 * RTCStats represents the stats object constructed by inspecting a specific
 * monitored object at a specific moment in time.
 *
 * @author Alex Andres
 */
public class RTCStats {

	/**
	 * The timestamp associated with this stats. The time is relative to the
	 * UNIX epoch. For statistics that came from a remote source (e.g., from
	 * received RTCP packets), timestamp represents the time at which the
	 * information arrived at the local endpoint.
	 */
	private final long timestamp;

	/**
	 * The type of this stats.
	 */
	private final RTCStatsType type;

	/**
	 * A unique id that is associated with the object that was inspected to
	 * produce this RTCStats object.
	 */
	private final String id;

	/**
	 * The stats data.
	 */
	private final Map<String, Object> members;


	protected RTCStats(long timestamp, RTCStatsType type, String id, Map<String, Object> members) {
		this.timestamp = timestamp;
		this.type = type;
		this.id = id;
		this.members = members;
	}

	/**
	 * Get the timestamp in microseconds. For statistics that came from a remote
	 * source (e.g., from received RTCP packets), timestamp represents the time
	 * at which the information arrived at the local endpoint.
	 *
	 * @return the timestamp in microseconds relative to the UNIX epoch.
	 */
	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * Get the type of the object that was inspected to produce the stats.
	 *
	 * @return the type of the inspected object.
	 */
	public RTCStatsType getType() {
		return type;
	}

	/**
	 * Get the unique id that is associated with the object that was inspected
	 * to produce this RTCStats object.
	 *
	 * @return the unique id representing this stats object.
	 */
	public String getId() {
		return id;
	}

	/**
	 * Returns map of member names to values. Returns as an ordered map so that
	 * the stats object can be serialized with a consistent ordering.
	 * <p>
	 * Values will be one of the following objects:
	 * - Boolean
	 * - Integer (for 32-bit signed integers)
	 * - Long (for 32-bit unsigned and 64-bit signed integers)
	 * - BigInteger (for 64-bit unsigned integers)
	 * - Double
	 * - String
	 * - The array form of any of the above (e.g., Integer[])
	 *
	 * @return the stats map.
	 */
	public Map<String, Object> getMembers() {
		return members;
	}

	@Override
	public String toString() {
		StringBuilder builder = new StringBuilder();
		builder.append(RTCStats.class.getSimpleName());
		builder.append("[ timestamp: ").append(timestamp)
				.append(", type: ").append(type)
				.append(", id: ").append(id);

		for (Map.Entry<String, Object> entry : members.entrySet()) {
			builder.append(", ").append(entry.getKey()).append(": ");
			appendValue(builder, entry.getValue());
		}
		builder.append("]");

		return builder.toString();
	}

	private static void appendValue(StringBuilder builder, Object value) {
		if (value instanceof Object[]) {
			Object[] arrayValue = (Object[]) value;
			builder.append('[');
			for (int i = 0; i < arrayValue.length; ++i) {
				if (i != 0) {
					builder.append(", ");
				}
				appendValue(builder, arrayValue[i]);
			}
			builder.append(']');
		}
		else if (value instanceof String) {
			// Enclose strings in quotes to make it clear they're strings.
			builder.append('"').append(value).append('"');
		}
		else {
			builder.append(value);
		}
	}
}
