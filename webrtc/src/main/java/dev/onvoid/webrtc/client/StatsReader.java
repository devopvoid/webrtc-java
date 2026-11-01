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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;

/**
 * Takes {@link PeerStats} from the reports of one peer. It keeps the previous
 * sample to compute bitrates and the recent packet loss over the time between
 * the two measurements, not between their arrival here, which a busy control
 * executor may delay. Used on the peer's control executor.
 */
final class StatsReader {

	private long lastMicros = -1;

	private long lastBytesSent;

	private long lastBytesReceived;

	/** The packets received and lost of each inbound stream, by id. */
	private Map<String, long[]> lastStreams = Collections.emptyMap();

	private long lastOutgoingBitrate = -1;

	private long lastIncomingBitrate = -1;

	private double lastRecentPacketLoss = -1;


	PeerStats read(RTCStatsReport report) {
		Map<String, RTCStats> stats = report.getStats();

		long bytesSent = 0;
		long bytesReceived = 0;
		long packetsReceived = 0;
		long packetsLost = 0;
		List<TransportStats> transports = new ArrayList<>();
		Map<String, long[]> streams = new HashMap<>();
		List<VideoStats> outbound = new ArrayList<>();
		List<VideoStats> inbound = new ArrayList<>();

		for (RTCStats entry : stats.values()) {
			Map<String, Object> values = entry.getAttributes();

			if (entry.getType() == RTCStatsType.TRANSPORT) {
				TransportStats transport = transport(stats, entry);

				bytesSent += transport.bytesSent();
				bytesReceived += transport.bytesReceived();
				transports.add(transport);
			}
			else if (entry.getType() == RTCStatsType.INBOUND_RTP) {
				long received = longValue(values.get("packetsReceived"));
				// Negative when more arrived than were expected, as duplicates do.
				long lost = Math.max(0, longValue(values.get("packetsLost")));

				packetsReceived += received;
				packetsLost += lost;
				streams.put(entry.getId(), new long[] { received, lost });

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

		// The report is a map without order: sorted, the same report gives the
		// same result.
		transports.sort(Comparator.comparing(TransportStats::id,
				Comparator.nullsFirst(Comparator.naturalOrder())));

		long micros = report.getTimestamp();
		long outgoingBitrate = -1;
		long incomingBitrate = -1;
		double recentPacketLoss = -1;

		if (lastMicros >= 0 && micros == lastMicros) {
			// WebRTC hands out a report again for a short while: the same
			// measurement, with the same values, measured from the same
			// sample.
			outgoingBitrate = lastOutgoingBitrate;
			incomingBitrate = lastIncomingBitrate;
			recentPacketLoss = lastRecentPacketLoss;
		}
		else {
			// A clock that went back leaves no interval to measure, and the
			// next sample is measured from this one.
			if (lastMicros >= 0 && micros > lastMicros) {
				double seconds = (micros - lastMicros) / 1e6;

				outgoingBitrate = bitrate(bytesSent, lastBytesSent, seconds);
				incomingBitrate = bitrate(bytesReceived, lastBytesReceived, seconds);
				recentPacketLoss = recentPacketLoss(streams);
			}

			lastMicros = micros;
			lastBytesSent = bytesSent;
			lastBytesReceived = bytesReceived;
			lastStreams = streams;
			lastOutgoingBitrate = outgoingBitrate;
			lastIncomingBitrate = incomingBitrate;
			lastRecentPacketLoss = recentPacketLoss;
		}

		return new PeerStats(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), inUse(transports),
				Collections.unmodifiableList(transports), bytesSent, bytesReceived,
				outgoingBitrate, incomingBitrate, lossRate(packetsLost, packetsReceived),
				recentPacketLoss, Collections.unmodifiableList(outbound),
				Collections.unmodifiableList(inbound), report);
	}

	private static TransportStats transport(Map<String, RTCStats> stats, RTCStats entry) {
		Map<String, Object> values = entry.getAttributes();
		String pairId = string(values.get("selectedCandidatePairId"));
		RTCStats pair = lookup(stats, pairId);
		String candidateType = null;
		Duration roundTripTime = null;

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

		return new TransportStats(entry.getId(), pairId, candidateType, roundTripTime,
				longValue(values.get("bytesSent")), longValue(values.get("bytesReceived")));
	}

	/**
	 * @param transports ordered by id.
	 *
	 * @return the transport with a candidate pair in use that carried the most
	 * bytes, the first of those that carried as many, or null.
	 */
	private static TransportStats inUse(List<TransportStats> transports) {
		TransportStats inUse = null;

		for (TransportStats transport : transports) {
			if (transport.selectedCandidatePairId() != null && (inUse == null
					|| bytes(transport) > bytes(inUse))) {
				inUse = transport;
			}
		}

		return inUse;
	}

	private static long bytes(TransportStats transport) {
		return transport.bytesSent() + transport.bytesReceived();
	}

	/**
	 * @param streams the packets received and lost of each inbound stream now.
	 *
	 * @return the share of packets lost of those expected since the last
	 * sample.
	 */
	private double recentPacketLoss(Map<String, long[]> streams) {
		long received = 0;
		long lost = 0;

		for (Map.Entry<String, long[]> stream : streams.entrySet()) {
			long[] now = stream.getValue();
			long[] before = lastStreams.get(stream.getKey());

			if (before == null || now[0] < before[0]) {
				// Came up since, or its counters started again: all it
				// counted is new.
				received += now[0];
				lost += now[1];
			}
			else {
				received += now[0] - before[0];
				// Fewer lost than before when late packets filled a gap.
				lost += Math.max(0, now[1] - before[1]);
			}
		}

		return lossRate(lost, received);
	}

	private static double lossRate(long lost, long received) {
		long expected = received + lost;

		return expected == 0 ? 0 : (double) lost / expected;
	}

	private static long bitrate(long bytes, long lastBytes, double seconds) {
		// Fewer bytes than before: the counter started again, such as on a
		// transport that replaced another, and what was sent meanwhile is
		// unknown.
		return bytes < lastBytes ? -1 : (long) ((bytes - lastBytes) * 8 / seconds);
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
