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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import org.junit.jupiter.api.Test;

class DiagnosticsTests {
	private final StatsReader reader = new StatsReader();

	@Test
	void cpuNeedsTimeAndSamples() {
		assertEquals(PeerDiagnostics.State.OBSERVING, cpu(0, "cpu").diagnostics().state());
		assertFalse(has(cpu(1, "cpu"), QualityCondition.VIDEO_CPU_LIMITED));
		assertFalse(has(cpu(2, "cpu"), QualityCondition.VIDEO_CPU_LIMITED));
		PeerStats limited = cpu(3, "cpu");
		assertTrue(has(limited, QualityCondition.VIDEO_CPU_LIMITED));
		assertEquals(PeerDiagnostics.State.DEGRADED, limited.diagnostics().state());
		assertTrue(has(cpu(4, "none"), QualityCondition.VIDEO_CPU_LIMITED));
		assertTrue(has(cpu(5, "cpu"), QualityCondition.VIDEO_CPU_LIMITED));
		cpu(6, "none");
		cpu(7, "none");
		assertTrue(has(cpu(8, "none"), QualityCondition.VIDEO_CPU_LIMITED));
		assertFalse(has(cpu(9, "none"), QualityCondition.VIDEO_CPU_LIMITED));
		assertEquals(PeerDiagnostics.State.HEALTHY, cpu(10, "none").diagnostics().state());
		assertTrue(has(limited, QualityCondition.VIDEO_CPU_LIMITED));
		assertThrows(UnsupportedOperationException.class, () -> limited.diagnostics().conditions().clear());
	}

	@Test
	void duplicatesDoNotConfirm() {
		cpu(0, "cpu");
		cpu(3, "cpu");
		for (int i = 0; i < 10; i++) {
			assertFalse(has(cpu(3, "cpu"), QualityCondition.VIDEO_CPU_LIMITED));
		}
		assertTrue(has(cpu(4, "cpu"), QualityCondition.VIDEO_CPU_LIMITED));
	}

	@Test
	void discontinuitiesDiscardEvidence() {
		cpu(0, "cpu");
		cpu(1, "cpu");
		assertFalse(has(cpu(10, "cpu"), QualityCondition.VIDEO_CPU_LIMITED));
		cpu(11, "cpu");
		assertTrue(has(cpu(13, "cpu"), QualityCondition.VIDEO_CPU_LIMITED));
		assertEquals(PeerDiagnostics.State.OBSERVING, cpu(2, "cpu").diagnostics().state());
		assertFalse(has(cpu(3, "cpu"), QualityCondition.VIDEO_CPU_LIMITED));
	}

	@Test
	void streamsNeedOwnEvidence() {
		for (int second = 0; second <= 6; second++) {
			Map<String, RTCStats> map = new HashMap<>();
			add(map, RTCStatsType.OUTBOUND_RTP, "v1", "kind", "video",
					"qualityLimitationReason", second % 2 == 0 ? "cpu" : "none");
			add(map, RTCStatsType.OUTBOUND_RTP, "v2", "kind", "video",
					"qualityLimitationReason", second % 2 == 1 ? "cpu" : "none");
			assertFalse(has(read(second, map), QualityCondition.VIDEO_CPU_LIMITED));
		}
	}

	@Test
	void missingVideoIsUnknown() {
		for (int second = 0; second <= 3; second++) cpu(second, "cpu");
		assertFalse(has(read(4, new HashMap<>()), QualityCondition.VIDEO_CPU_LIMITED));
		assertFalse(has(cpu(5, "cpu"), QualityCondition.VIDEO_CPU_LIMITED));
	}

	@Test
	void lossHasHysteresis() {
		loss(0, 0, 0);
		for (int second = 1; second <= 3; second++) {
			assertFalse(has(loss(second, second * 90, second * 10), QualityCondition.HIGH_PACKET_LOSS));
		}
		PeerStats high = loss(4, 360, 40);
		assertTrue(has(high, QualityCondition.HIGH_PACKET_LOSS));
		assertTrue(has(loss(5, 457, 43), QualityCondition.HIGH_PACKET_LOSS)); // 3% dead band
		assertTrue(has(loss(6, 555, 45), QualityCondition.HIGH_PACKET_LOSS)); // 2% starts recovery
		loss(7, 653, 47);
		loss(8, 751, 49);
		PeerStats recovered = loss(9, 849, 51);
		assertFalse(has(recovered, QualityCondition.HIGH_PACKET_LOSS));
		assertEquals(PeerDiagnostics.State.HEALTHY, recovered.diagnostics().state());
	}

	@Test
	void turnChecksBothEndsAndAllTransports() {
		Map<String, RTCStats> map = new HashMap<>();
		add(map, RTCStatsType.TRANSPORT, "transport", "selectedCandidatePairId", "pair");
		add(map, RTCStatsType.CANDIDATE_PAIR, "pair", "localCandidateId", "local", "remoteCandidateId", "remote");
		add(map, RTCStatsType.LOCAL_CANDIDATE, "local", "candidateType", "host");
		add(map, RTCStatsType.REMOTE_CANDIDATE, "remote", "candidateType", "relay");
		add(map, RTCStatsType.TRANSPORT, "primary", "selectedCandidatePairId", "direct-pair", "bytesSent", 10000L);
		add(map, RTCStatsType.CANDIDATE_PAIR, "direct-pair", "localCandidateId", "direct");
		add(map, RTCStatsType.LOCAL_CANDIDATE, "direct", "candidateType", "host");
		PeerStats first = read(0, map);
		assertFalse(first.transport().relayed());
		assertTrue(first.transports().stream().anyMatch(TransportStats::relayed));
		assertTrue(has(first, QualityCondition.TURN_IN_USE));
		assertEquals(PeerDiagnostics.State.OBSERVING, first.diagnostics().state());
		assertEquals(PeerDiagnostics.State.HEALTHY, read(1, map).diagnostics().state());
		add(map, RTCStatsType.REMOTE_CANDIDATE, "remote", "candidateType", "host");
		assertFalse(has(read(2, map), QualityCondition.TURN_IN_USE));
		add(map, RTCStatsType.LOCAL_CANDIDATE, "local", "candidateType", "relay");
		assertTrue(has(read(3, map), QualityCondition.TURN_IN_USE));
	}

	@Test
	void audioIsTypedAndOptional() {
		Map<String, RTCStats> map = audio(20);
		map.get("audio").getAttributes().putAll(attributes("jitter", 0.012,
				"concealedSamples", 42L, "concealmentEvents", 2L, "audioLevel", 0.25,
				"totalSamplesReceived", 48000L, "codecId", "codec"));
		add(map, RTCStatsType.CODEC, "codec", "mimeType", "audio/opus");
		add(map, RTCStatsType.MEDIA_SOURCE, "source", "audioLevel", 0.5, "trackIdentifier", "microphone");
		add(map, RTCStatsType.OUTBOUND_RTP, "out", "kind", "audio", "mediaSourceId", "source", "codecId", "codec");
		PeerStats sample = read(0, map);
		AudioStats audio = sample.inboundAudio().get(0);
		assertEquals(Duration.ofMillis(12), audio.jitter());
		assertEquals(42L, audio.concealedSamples());
		assertEquals(2L, audio.concealmentEvents());
		assertEquals(0.25, audio.audioLevel());
		assertEquals(48000L, audio.totalSamplesReceived());
		assertEquals("audio/opus", audio.codec());
		assertNull(audio.pcmCallbacks());
		assertEquals(0.5, sample.outboundAudio().get(0).audioLevel());
		assertEquals("microphone", sample.outboundAudio().get(0).trackId());
		AudioStats missing = read(1, audio(30)).inboundAudio().get(0);
		assertNull(missing.jitter());
		assertNull(missing.concealedSamples());
		assertNull(missing.audioLevel());
		assertThrows(UnsupportedOperationException.class, () -> sample.inboundAudio().clear());
	}

	@Test
	void pcmRequiresObservedSinkAndPackets() {
		for (int second = 0; second < 8; second++) {
			assertFalse(has(read(second, audio(second * 10)), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
		}
		for (int second = 8; second <= 11; second++) {
			assertFalse(has(pcm(second, second * 10, 0, 1), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
		}
		assertTrue(has(pcm(12, 120, 0, 1), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
		pcm(13, 130, 1, 1);
		pcm(14, 140, 2, 1);
		pcm(15, 150, 3, 1);
		assertFalse(has(pcm(16, 160, 4, 1), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
	}

	@Test
	void silenceAndAbsentPacketsAreNotPcmFailures() {
		for (int second = 0; second <= 6; second++) {
			assertFalse(has(pcm(second, 100, 0, 1), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
		}
		for (int second = 7; second <= 12; second++) {
			Map<String, RTCStats> map = audio(second * 100);
			map.get("audio").getAttributes().put("audioLevel", 0.0);
			PeerStats stats = reader.read(report(second, map), Collections.singletonMap("track", new long[] { 1, second }));
			assertFalse(has(stats, QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
		}
	}

	@Test
	void pcmCounterAndSinkChangesResetWindow() {
		for (int second = 0; second <= 4; second++) pcm(second, 10 * second, 10, 1);
		assertFalse(has(pcm(5, 50, 0, 1), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
		pcm(6, 60, 0, 1);
		pcm(7, 70, 0, 1);
		assertFalse(has(pcm(8, 80, 0, 2), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
		pcm(9, 90, 0, 2);
		pcm(10, 100, 0, 2);
		assertFalse(has(pcm(11, 1, 0, 2), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
		assertFalse(has(read(12, audio(11)), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM));
	}

	@Test
	void exportExcludesSensitiveAttributes() {
		Map<String, RTCStats> map = audio(100);
		map.get("audio").getAttributes().putAll(attributes("codecId", "codec",
				"address", "192.0.2.123", "url", "turn:secret.example", "username", "private-user",
				"credential", "private-password", "sdp", "private-sdp", "certificate", "private-cert"));
		add(map, RTCStatsType.CODEC, "codec", "mimeType", "audio/opus", "sdpFmtpLine", "private-fmtp");
		PeerStats sample = read(0, map);
		String json = sample.diagnosticReport();
		assertInstanceOf(Map.class, Json.parse(json));
		assertTrue(json.contains("audio/opus"));
		assertTrue(json.contains("javaVersion"));
		assertTrue(json.contains("architecture"));
		for (String secret : new String[] { "192.0.2.123", "secret.example", "private-", "track", "codecId" }) {
			assertFalse(json.contains(secret), json);
		}
		map.get("codec").getAttributes().put("mimeType", "audio/private-codec");
		assertFalse(read(1, map).diagnosticReport().contains("private-codec"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void exportIncludesExplanationAndPolicy() {
		cpu(0, "cpu");
		cpu(1, "cpu");
		cpu(2, "cpu");
		Map<String, Object> report = (Map<String, Object>) Json.parse(cpu(3, "cpu").diagnosticReport());
		Map<String, Object> policy = (Map<String, Object>) report.get("policy");
		assertEquals(3.0, policy.get("confirmationSeconds"));
		assertEquals(3.0, policy.get("minimumMeasurements"));
		assertEquals(0.05, policy.get("lossEnter"));
		assertEquals(0.02, policy.get("lossExit"));
		List<Map<String, Object>> causes = (List<Map<String, Object>>) report.get("conditions");
		assertEquals("VIDEO_CPU_LIMITED", causes.get(0).get("cause"));
		assertEquals(QualityCondition.VIDEO_CPU_LIMITED.reason(), causes.get(0).get("reason"));
		assertEquals(true, causes.get(0).get("problem"));
	}

	@Test
	void exportHandlesNonFiniteMeasurements() {
		Map<String, RTCStats> map = audio(10);
		map.get("audio").getAttributes().putAll(attributes("jitter", Double.NaN, "audioLevel", Double.POSITIVE_INFINITY));
		add(map, RTCStatsType.OUTBOUND_RTP, "video", "kind", "video", "framesPerSecond", Double.NaN);
		PeerStats sample = read(0, map);
		assertNull(sample.inboundAudio().get(0).jitter());
		assertNull(sample.inboundAudio().get(0).audioLevel());
		assertInstanceOf(Map.class, Json.parse(sample.diagnosticReport()));
	}

	@Test
	@SuppressWarnings("unchecked")
	void eventsOnlyOnChangesAndCanStop() throws Throwable {
		AtomicReference<Consumer<PeerStats>> callback = new AtomicReference<>();
		boolean[] stopped = { false };
		// Exercise the default API without a native connection or timer.
		Peer peer = (Peer) Proxy.newProxyInstance(Peer.class.getClassLoader(), new Class<?>[] { Peer.class },
				(proxy, method, args) -> {
					if (method.getName().equals("onStats")) {
						callback.set((Consumer<PeerStats>) args[1]);
						return (Subscription) () -> stopped[0] = true;
					}
					if (method.isDefault()) {
						return java.lang.invoke.MethodHandles.privateLookupIn(Peer.class, java.lang.invoke.MethodHandles.lookup())
								.unreflectSpecial(method, Peer.class).bindTo(proxy).invokeWithArguments(args);
					}
					throw new UnsupportedOperationException(method.getName());
				});
		List<PeerDiagnostics> events = new ArrayList<>();
		Subscription subscription = peer.onDiagnostics(Duration.ofSeconds(1), events::add);
		callback.get().accept(cpu(0, "cpu"));
		callback.get().accept(cpu(1, "cpu"));
		callback.get().accept(cpu(2, "cpu"));
		callback.get().accept(cpu(3, "cpu"));
		callback.get().accept(cpu(4, "cpu"));
		assertEquals(3, events.size()); // observing, healthy, degraded
		assertEquals(PeerDiagnostics.State.DEGRADED, events.get(2).state());
		subscription.close();
		assertTrue(stopped[0]);
	}

	private PeerStats cpu(int second, String reason) {
		Map<String, RTCStats> map = new HashMap<>();
		add(map, RTCStatsType.OUTBOUND_RTP, "video", "kind", "video", "qualityLimitationReason", reason);
		return read(second, map);
	}

	private PeerStats loss(int second, long received, long lost) {
		Map<String, RTCStats> map = audio(received);
		map.get("audio").getAttributes().put("packetsLost", lost);
		return read(second, map);
	}

	private PeerStats pcm(int second, long packets, long callbacks, long generation) {
		return reader.read(report(second, audio(packets)),
				Collections.singletonMap("track", new long[] { generation, callbacks }));
	}

	private PeerStats read(int second, Map<String, RTCStats> map) {
		return reader.read(report(second, map));
	}

	private static RTCStatsReport report(int second, Map<String, RTCStats> map) {
		return new RTCStatsReport(map, 1_700_000_000_000_000L + second * 1_000_000L) { };
	}

	private static Map<String, RTCStats> audio(long received) {
		Map<String, RTCStats> map = new HashMap<>();
		add(map, RTCStatsType.INBOUND_RTP, "audio", "kind", "audio", "trackIdentifier", "track",
				"packetsReceived", received, "packetsLost", 0L);
		return map;
	}

	private static boolean has(PeerStats stats, QualityCondition cause) {
		return stats.diagnostics().conditions().contains(cause);
	}

	private static void add(Map<String, RTCStats> map, RTCStatsType type, String id, Object... values) {
		map.put(id, new RTCStats(0, type, id, attributes(values)) { });
	}

	private static Map<String, Object> attributes(Object... values) {
		Map<String, Object> map = new HashMap<>();
		for (int i = 0; i < values.length; i += 2) map.put((String) values[i], values[i + 1]);
		return map;
	}
}
