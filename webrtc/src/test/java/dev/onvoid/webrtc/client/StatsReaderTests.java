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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

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

	private static RTCStatsReport report(long micros, long bytesSent, long bytesReceived) {
		Map<String, Object> values = new HashMap<>();
		values.put("bytesSent", bytesSent);
		values.put("bytesReceived", bytesReceived);

		Map<String, RTCStats> stats = new HashMap<>();
		stats.put("T01", new RTCStats(micros, RTCStatsType.TRANSPORT, "T01", values) { });

		return new RTCStatsReport(stats, micros) { };
	}

}
