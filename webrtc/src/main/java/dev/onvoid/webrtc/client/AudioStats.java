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

/** Immutable statistics of one audio RTP stream. Missing measurements are null. */
public final class AudioStats {
	private final String id;
	private final String trackId;
	private final String codec;
	private final Long packetsReceived;
	private final Duration jitter;
	private final Long concealedSamples;
	private final Long concealmentEvents;
	private final Double audioLevel;
	private final Long totalSamplesReceived;
	private final Long pcmCallbacks;
	final long pcmGeneration;

	AudioStats(String id, String trackId, String codec, Long packetsReceived,
			Duration jitter, Long concealedSamples, Long concealmentEvents,
			Double audioLevel, Long totalSamplesReceived, Long pcmCallbacks, long pcmGeneration) {
		this.id = id;
		this.trackId = trackId;
		this.codec = codec;
		this.packetsReceived = packetsReceived;
		this.jitter = jitter;
		this.concealedSamples = concealedSamples;
		this.concealmentEvents = concealmentEvents;
		this.audioLevel = audioLevel;
		this.totalSamplesReceived = totalSamplesReceived;
		this.pcmCallbacks = pcmCallbacks;
		this.pcmGeneration = pcmGeneration;
	}

	/** @return the RTP statistics id, for correlating successive measurements. */
	public String id() {
		return id;
	}

	/** @return the track identifier, or null. */
	public String trackId() {
		return trackId;
	}

	/** @return the negotiated codec MIME type, or null. */
	public String codec() {
		return codec;
	}

	/** @return cumulative packets received, or null for outbound or unavailable data. */
	public Long packetsReceived() {
		return packetsReceived;
	}

	/** @return interarrival jitter, or null if unavailable. */
	public Duration jitter() {
		return jitter;
	}

	/** @return cumulative samples replaced by concealment, or null. */
	public Long concealedSamples() {
		return concealedSamples;
	}

	/** @return cumulative concealment events, or null. */
	public Long concealmentEvents() {
		return concealmentEvents;
	}

	/** @return linear audio level from 0 to 1, or null. Zero can be silence. */
	public Double audioLevel() {
		return audioLevel;
	}

	/** @return cumulative received samples reported by WebRTC, or null. */
	public Long totalSamplesReceived() {
		return totalSamplesReceived;
	}

	/**
	 * @return cumulative PCM callbacks across this track's onAudio subscriptions,
	 * or null when none is attached. Counts sink ingress, before application queues.
	 * Several subscriptions can count the same audio more than once.
	 */
	public Long pcmCallbacks() {
		return pcmCallbacks;
	}
}
