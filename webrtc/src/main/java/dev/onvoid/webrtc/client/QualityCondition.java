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

/** An explainable observation, without a combined quality score. */
public enum QualityCondition {
	VIDEO_CPU_LIMITED("Video is limited by CPU", true),
	HIGH_PACKET_LOSS("High packet loss over several measurements", true),
	TURN_IN_USE("The connection uses TURN", false),
	AUDIO_PACKETS_WITHOUT_PCM("Audio receives packets but no PCM reaches subscribed sinks", true);

	private final String reason;
	private final boolean problem;

	QualityCondition(String reason, boolean problem) {
		this.reason = reason;
		this.problem = problem;
	}

	/** @return a human-readable explanation. */
	public String reason() {
		return reason;
	}

	/** @return whether this condition indicates degraded media, rather than information. */
	public boolean isProblem() {
		return problem;
	}
}
