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

package dev.onvoid.webrtc.client;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;

/**
 * Takes {@link PeerStats} from the reports of one peer. It keeps the previous
 * sample to compute bitrates. Used on the peer's control executor.
 */
final class StatsReader {

	private long lastNanos = -1;

	private long lastBytesSent;

	private long lastBytesReceived;


	PeerStats read(RTCStatsReport report) {
		Map<String, RTCStats> stats = report.getStats();

		long bytesSent = 0;
		long bytesReceived = 0;
		String candidateType = null;
		Duration roundTripTime = null;
		long packetsReceived = 0;
		long packetsLost = 0;
		List<VideoStats> outbound = new ArrayList<>();
		List<VideoStats> inbound = new ArrayList<>();

		for (RTCStats entry : stats.values()) {
			Map<String, Object> values = entry.getAttributes();

			if (entry.getType() == RTCStatsType.TRANSPORT) {
				bytesSent += longValue(values.get("bytesSent"));
				bytesReceived += longValue(values.get("bytesReceived"));

				RTCStats pair = lookup(stats, values.get("selectedCandidatePairId"));

				if (pair != null) {
					Object rtt = pair.getAttributes().get("currentRoundTripTime");

					if (rtt instanceof Number) {
						roundTripTime = Duration.ofNanos((long) (((Number) rtt).doubleValue() * 1e9));
					}

					RTCStats local = lookup(stats, pair.getAttributes().get("localCandidateId"));

					if (local != null) {
						candidateType = string(local.getAttributes().get("candidateType"));
					}
				}
			}
			else if (entry.getType() == RTCStatsType.INBOUND_RTP) {
				packetsReceived += longValue(values.get("packetsReceived"));
				packetsLost += Math.max(0, longValue(values.get("packetsLost")));

				if ("video".equals(values.get("kind"))) {
					inbound.add(video(stats, values, string(values.get("trackIdentifier")), null));
				}
			}
			else if (entry.getType() == RTCStatsType.OUTBOUND_RTP) {
				if ("video".equals(values.get("kind"))) {
					RTCStats source = lookup(stats, values.get("mediaSourceId"));
					String trackId = source == null ? null : string(source.getAttributes().get("trackIdentifier"));

					outbound.add(video(stats, values, trackId, string(values.get("qualityLimitationReason"))));
				}
			}
		}

		long now = System.nanoTime();
		long outgoingBitrate = -1;
		long incomingBitrate = -1;

		if (lastNanos >= 0 && now > lastNanos) {
			double seconds = (now - lastNanos) / 1e9;

			outgoingBitrate = (long) (Math.max(0, bytesSent - lastBytesSent) * 8 / seconds);
			incomingBitrate = (long) (Math.max(0, bytesReceived - lastBytesReceived) * 8 / seconds);
		}

		lastNanos = now;
		lastBytesSent = bytesSent;
		lastBytesReceived = bytesReceived;

		long packetsExpected = packetsReceived + packetsLost;
		double packetLoss = packetsExpected == 0 ? 0 : (double) packetsLost / packetsExpected;

		return new PeerStats(Instant.now(), candidateType, roundTripTime, bytesSent, bytesReceived,
				outgoingBitrate, incomingBitrate, packetLoss,
				Collections.unmodifiableList(outbound), Collections.unmodifiableList(inbound), report);
	}

	private static VideoStats video(Map<String, RTCStats> stats, Map<String, Object> values,
			String trackId, String qualityLimitation) {
		RTCStats codec = lookup(stats, values.get("codecId"));

		return new VideoStats(trackId,
				codec == null ? null : string(codec.getAttributes().get("mimeType")),
				(int) longValue(values.get("frameWidth")),
				(int) longValue(values.get("frameHeight")),
				doubleValue(values.get("framesPerSecond")),
				qualityLimitation);
	}

	private static RTCStats lookup(Map<String, RTCStats> stats, Object id) {
		return id == null ? null : stats.get(id.toString());
	}

	private static long longValue(Object value) {
		return value instanceof Number ? ((Number) value).longValue() : 0;
	}

	private static double doubleValue(Object value) {
		return value instanceof Number ? ((Number) value).doubleValue() : 0;
	}

	private static String string(Object value) {
		return value == null ? null : value.toString();
	}
}
