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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsType;

/** Temporal evidence, confined to the statistics reader's control executor. */
final class DiagnosticsReader {
	static final Duration WINDOW = Duration.ofSeconds(3);
	static final Duration MAX_GAP = Duration.ofSeconds(5);
	static final int MIN_SAMPLES = 3;
	static final double LOSS_ENTER = 0.05;
	static final double LOSS_EXIT = 0.02;

	private final Map<String, Gate> gates = new HashMap<>();
	private Map<String, AudioStats> lastAudio = new HashMap<>();
	private Instant lastTime;
	private PeerDiagnostics last;

	PeerDiagnostics read(Instant time, double loss, List<TransportStats> transports,
			Map<String, RTCStats> stats, List<AudioStats> audio) {
		if (time.equals(lastTime)) {
			return last;
		}
		boolean continuous = lastTime != null && time.isAfter(lastTime)
				&& Duration.between(lastTime, time).compareTo(MAX_GAP) <= 0;
		if (!continuous) {
			gates.clear();
			lastAudio.clear();
		}
		Set<String> seen = new HashSet<>();
		Set<QualityCondition> conditions = EnumSet.noneOf(QualityCondition.class);
		update("loss", QualityCondition.HIGH_PACKET_LOSS,
				!continuous || !Double.isFinite(loss) || loss < 0 ? null
						: loss >= LOSS_ENTER ? Boolean.TRUE : loss <= LOSS_EXIT ? Boolean.FALSE : null,
				time, seen, conditions, loss > LOSS_EXIT && loss < LOSS_ENTER && continuous);

		for (RTCStats entry : stats.values()) {
			Map<String, Object> values = entry.getAttributes();
			if (entry.getType() == RTCStatsType.OUTBOUND_RTP && "video".equals(values.get("kind"))) {
				Object reason = values.get("qualityLimitationReason");
				update("cpu:" + entry.getId(), QualityCondition.VIDEO_CPU_LIMITED,
						"cpu".equals(reason) ? Boolean.TRUE
								: "none".equals(reason) || "bandwidth".equals(reason) || "other".equals(reason)
								? Boolean.FALSE : null, time, seen, conditions, false);
			}
		}
		Map<String, AudioStats> nextAudio = new HashMap<>();
		for (AudioStats now : audio) {
			AudioStats before = lastAudio.get(now.id());
			Boolean stalled = null;
			if (before != null && now.packetsReceived() != null && before.packetsReceived() != null
					&& now.pcmCallbacks() != null && before.pcmCallbacks() != null
					&& now.pcmGeneration == before.pcmGeneration
					&& java.util.Objects.equals(now.trackId(), before.trackId())
					&& now.packetsReceived() >= before.packetsReceived()
					&& now.pcmCallbacks() >= before.pcmCallbacks()) {
				stalled = now.packetsReceived() > before.packetsReceived()
						&& now.pcmCallbacks().equals(before.pcmCallbacks());
			}
			update("pcm:" + now.id(), QualityCondition.AUDIO_PACKETS_WITHOUT_PCM,
					stalled, time, seen, conditions, false);
			nextAudio.put(now.id(), now);
		}
		gates.keySet().retainAll(seen);
		if (transports.stream().anyMatch(TransportStats::relayed)) {
			conditions.add(QualityCondition.TURN_IN_USE);
		}
		lastAudio = nextAudio;
		lastTime = time;
		last = new PeerDiagnostics(time, continuous && !stats.isEmpty(), conditions);
		return last;
	}

	private void update(String key, QualityCondition condition, Boolean evidence, Instant time,
			Set<String> seen, Set<QualityCondition> conditions, boolean deadband) {
		seen.add(key);
		Gate gate = gates.computeIfAbsent(key, ignored -> new Gate());
		if (deadband) {
			gate.since = null;
			gate.count = 0;
		}
		else if (evidence == null) {
			// Unknown data cannot confirm an old problem or count as recovery.
			gate.active = false;
			gate.since = null;
			gate.count = 0;
		}
		else if (evidence == gate.active) {
			gate.since = null;
			gate.count = 0;
		}
		else {
			if (gate.since == null) {
				gate.since = time;
			}
			gate.count++;
			if (gate.count >= MIN_SAMPLES && Duration.between(gate.since, time).compareTo(WINDOW) >= 0) {
				gate.active = evidence;
				gate.since = null;
				gate.count = 0;
			}
		}
		if (gate.active) {
			conditions.add(condition);
		}
	}

	private static final class Gate {
		boolean active;
		Instant since;
		int count;
	}
}
