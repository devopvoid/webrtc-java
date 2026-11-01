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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;

import org.junit.jupiter.api.Test;

class StatsReaderTests {

	private static final long START = 1_700_000_000_000_000L;

	private static final long SECOND = 1_000_000;

	private final StatsReader reader = new StatsReader();


	@Test
	void bitrateFromReportTimes() {
		// Read one right after the other, as a busy control executor hands
		// them on: the reports were measured a second apart.
		PeerStats first = reader.read(report(START, 5_000, 2_000));
		PeerStats second = reader.read(report(START + SECOND, 6_000, 2_500));

		assertEquals(-1, first.outgoingBitrate());
		assertEquals(-1, first.incomingBitrate());
		assertEquals(8_000, second.outgoingBitrate());
		assertEquals(4_000, second.incomingBitrate());
	}

	@Test
	void timestampOfReport() {
		PeerStats stats = reader.read(report(START + 250, 0, 0));

		assertEquals(Instant.ofEpochSecond(1_700_000_000, 250_000), stats.timestamp());
	}

	@Test
	void repeatedReportKeepsBitrates() {
		// WebRTC hands out the report it cached again. The one after it is
		// measured from the sample before.
		reader.read(report(START, 0, 0));
		reader.read(report(START + SECOND, 1_000, 1_000));
		PeerStats repeated = reader.read(report(START + SECOND, 1_000, 1_000));
		PeerStats next = reader.read(report(START + 2 * SECOND, 3_000, 1_000));

		assertEquals(8_000, repeated.outgoingBitrate());
		assertEquals(8_000, repeated.incomingBitrate());
		assertEquals(16_000, next.outgoingBitrate());
		assertEquals(0, next.incomingBitrate());
	}

	@Test
	void repeatedFirstReport() {
		reader.read(report(START, 1_000, 1_000));
		PeerStats repeated = reader.read(report(START, 1_000, 1_000));

		assertEquals(-1, repeated.outgoingBitrate());
		assertEquals(-1, repeated.incomingBitrate());
	}

	@Test
	void clockWentBack() {
		reader.read(report(START, 0, 0));
		PeerStats back = reader.read(report(START - SECOND, 1_000, 1_000));
		PeerStats next = reader.read(report(START, 2_000, 3_000));

		assertEquals(-1, back.outgoingBitrate());
		assertEquals(-1, back.incomingBitrate());
		assertEquals(8_000, next.outgoingBitrate());
		assertEquals(16_000, next.incomingBitrate());
	}

	@Test
	void counterStartedAgain() {
		// Fewer bytes sent than before. Received counts on.
		reader.read(report(START, 10_000, 1_000));
		PeerStats reset = reader.read(report(START + SECOND, 500, 2_000));
		PeerStats next = reader.read(report(START + 2 * SECOND, 1_500, 2_000));

		assertEquals(-1, reset.outgoingBitrate());
		assertEquals(8_000, reset.incomingBitrate());
		assertEquals(8_000, next.outgoingBitrate());
		assertEquals(0, next.incomingBitrate());
	}

	@Test
	void eachTransport() {
		// Unbundled: a transport for each m-line. T03 carried the most, but
		// has no candidate pair in use.
		Map<String, RTCStats> stats = new HashMap<>();
		transport(stats, "T02", 4_000, 1_000, "CP02", "relay", 0.2);
		transport(stats, "T01", 1_000, 500, "CP01", "host", 0.05);
		transport(stats, "T03", 9_000, 9_000, null, null, 0);

		PeerStats sample = reader.read(new RTCStatsReport(stats, START) { });

		assertEquals(Arrays.asList("T01", "T02", "T03"),
				sample.transports().stream().map(TransportStats::id).collect(Collectors.toList()));
		assertEquals("host", sample.transports().get(0).candidateType());
		assertEquals(Duration.ofMillis(50), sample.transports().get(0).roundTripTime());
		assertNull(sample.transports().get(2).selectedCandidatePairId());

		assertSame(sample.transports().get(1), sample.transport());
		assertEquals("relay", sample.candidateType());
		assertEquals(Duration.ofMillis(200), sample.roundTripTime());
		assertEquals(14_000, sample.bytesSent());
		assertEquals(10_500, sample.bytesReceived());
	}

	@Test
	void equalTransportsTakeLowestId() {
		Map<String, RTCStats> stats = new HashMap<>();
		transport(stats, "T02", 1_000, 0, "CP02", "srflx", 0.1);
		transport(stats, "T01", 0, 1_000, "CP01", "host", 0.1);

		assertEquals("T01", reader.read(new RTCStatsReport(stats, START) { }).transport().id());
	}

	@Test
	void noPairInUse() {
		PeerStats sample = reader.read(report(START, 1_000, 1_000));

		assertNull(sample.transport());
		assertNull(sample.candidateType());
		assertNull(sample.roundTripTime());
		assertEquals(1, sample.transports().size());
	}

	@Test
	void recentPacketLoss() {
		PeerStats first = reader.read(inbound(START, 1_000, 0));
		PeerStats lossy = reader.read(inbound(START + SECOND, 1_900, 100));
		PeerStats repeated = reader.read(inbound(START + SECOND, 1_900, 100));
		PeerStats clean = reader.read(inbound(START + 2 * SECOND, 2_900, 100));
		PeerStats idle = reader.read(inbound(START + 3 * SECOND, 2_900, 100));

		assertEquals(-1, first.recentPacketLoss());
		// 100 of the 1000 expected since the first sample.
		assertEquals(0.1, lossy.recentPacketLoss(), 1e-9);
		assertEquals(0.05, lossy.packetLoss(), 1e-9);
		assertEquals(0.1, repeated.recentPacketLoss(), 1e-9);
		assertEquals(0, clean.recentPacketLoss());
		assertEquals(0, idle.recentPacketLoss());
	}

	@Test
	void recentLossOfNewAndRestartedStreams() {
		Map<String, RTCStats> before = new HashMap<>();
		stream(before, START, "IA", 10_000, 0);
		stream(before, START, "IB", 10_000, 0);
		reader.read(new RTCStatsReport(before, START) { });

		// IA started again, IC came up: what they count is all new. IB lost
		// none of its 800 since: 30 lost of 1000 expected.
		Map<String, RTCStats> after = new HashMap<>();
		long micros = START + SECOND;
		stream(after, micros, "IA", 90, 10);
		stream(after, micros, "IB", 10_800, 0);
		stream(after, micros, "IC", 80, 20);
		PeerStats sample = reader.read(new RTCStatsReport(after, micros) { });

		assertEquals(30 / 1_000.0, sample.recentPacketLoss(), 1e-9);
	}

	@Test
	void clockWentBackLeavesNoRecentLoss() {
		reader.read(inbound(START, 1_000, 0));
		PeerStats back = reader.read(inbound(START - SECOND, 1_500, 0));
		PeerStats next = reader.read(inbound(START, 1_900, 100));

		assertEquals(-1, back.recentPacketLoss());
		assertEquals(0.2, next.recentPacketLoss(), 1e-9);
	}

	private static RTCStatsReport inbound(long micros, long packetsReceived, long packetsLost) {
		Map<String, RTCStats> stats = new HashMap<>();
		stream(stats, micros, "IT01", packetsReceived, packetsLost);

		return new RTCStatsReport(stats, micros) { };
	}

	private static void stream(Map<String, RTCStats> stats, long micros, String id,
			long packetsReceived, long packetsLost) {
		Map<String, Object> values = new HashMap<>();
		values.put("kind", "audio");
		values.put("packetsReceived", packetsReceived);
		values.put("packetsLost", packetsLost);

		stats.put(id, new RTCStats(micros, RTCStatsType.INBOUND_RTP, id, values) { });
	}

	private static void transport(Map<String, RTCStats> stats, String id, long bytesSent,
			long bytesReceived, String pairId, String candidateType, double rttSeconds) {
		Map<String, Object> values = new HashMap<>();
		values.put("bytesSent", bytesSent);
		values.put("bytesReceived", bytesReceived);

		if (pairId != null) {
			values.put("selectedCandidatePairId", pairId);

			Map<String, Object> pair = new HashMap<>();
			pair.put("currentRoundTripTime", rttSeconds);
			pair.put("localCandidateId", "L" + id);
			stats.put(pairId, new RTCStats(START, RTCStatsType.CANDIDATE_PAIR, pairId, pair) { });

			Map<String, Object> local = new HashMap<>();
			local.put("candidateType", candidateType);
			stats.put("L" + id, new RTCStats(START, RTCStatsType.LOCAL_CANDIDATE, "L" + id, local) { });
		}

		stats.put(id, new RTCStats(START, RTCStatsType.TRANSPORT, id, values) { });
	}

	private static RTCStatsReport report(long micros, long bytesSent, long bytesReceived) {
		Map<String, Object> values = new HashMap<>();
		values.put("bytesSent", bytesSent);
		values.put("bytesReceived", bytesReceived);

		Map<String, RTCStats> stats = new HashMap<>();
		stats.put("T01", new RTCStats(micros, RTCStatsType.TRANSPORT, "T01", values) { });

		return new RTCStatsReport(stats, micros) { };
	}

}
