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

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * An immutable diagnosis at a measurement time. TURN alone is informational.
 * <p>
 * Media problems and recovery require three distinct measurements spanning at
 * least three seconds. Packet loss enters at 5% and recovers at 2%; the band
 * between retains the current state. Missing evidence, clock rollback and gaps
 * longer than five seconds discard temporal history. Cached reports do not advance it.
 */
public final class PeerDiagnostics {
	/** Observation is limited to the conditions supported by this API. */
	public enum State {
		/** No continuous measurement interval is available yet. */
		OBSERVING,
		/** No supported media problem has been confirmed; not a quality guarantee. */
		HEALTHY,
		/** At least one supported media problem is confirmed. */
		DEGRADED
	}

	private final Instant timestamp;
	private final State state;
	private final Set<QualityCondition> conditions;

	PeerDiagnostics(Instant timestamp, boolean measured, Set<QualityCondition> conditions) {
		this.timestamp = timestamp;
		this.conditions = Collections.unmodifiableSet(conditions.isEmpty()
				? EnumSet.noneOf(QualityCondition.class) : EnumSet.copyOf(conditions));
		this.state = conditions.stream().anyMatch(QualityCondition::isProblem)
				? State.DEGRADED : measured ? State.HEALTHY : State.OBSERVING;
	}

	/** @return when the underlying statistics were measured. */
	public Instant timestamp() {
		return timestamp;
	}

	/** @return the diagnosis state, not a quality score or guarantee. */
	public State state() {
		return state;
	}

	/** @return the active causes; an empty set means none was confirmed. */
	public Set<QualityCondition> conditions() {
		return conditions;
	}

	@Override
	public String toString() {
		return state + " " + conditions;
	}
}
