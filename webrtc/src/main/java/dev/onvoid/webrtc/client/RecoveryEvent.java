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

/** A notification of ICE recovery on the existing peer, delivered on its event executor. */
public final class RecoveryEvent {

	public enum State {
		/** Waiting for a brief interruption to pass. */
		WAITING,
		/** Refreshing credentials and starting the numbered attempt. */
		ATTEMPTING,
		/** An attempt failed; another is scheduled after the backoff. */
		RETRYING,
		/** Connectivity returned. */
		RECOVERED,
		/** The budget was exhausted. The peer remains open for application action. */
		EXHAUSTED
	}

	private final State state;
	private final int attempt;
	private final Throwable cause;

	RecoveryEvent(State state, int attempt, Throwable cause) {
		this.state = state;
		this.attempt = attempt;
		this.cause = cause;
	}

	public State state() { return state; }

	/** @return the number of attempts started in this outage, zero during the grace period. */
	public int attempt() { return attempt; }

	/** @return the failure of the last attempt, or null. */
	public Throwable cause() { return cause; }
}
