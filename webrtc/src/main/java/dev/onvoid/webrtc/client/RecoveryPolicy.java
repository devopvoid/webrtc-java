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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.internal.Timeouts;

/**
 * Opt-in ICE recovery on the same peer. It never reconnects signaling or creates
 * a new session, remote tracks or data channels. Signaling must be usable for
 * an ICE restart to succeed. A return to CONNECTED resets the attempt budget.
 */
public final class RecoveryPolicy {

	final Duration grace;
	final Duration initialBackoff;
	final Duration maxBackoff;
	final Duration attemptTimeout;
	final int maxAttempts;
	final Function<Integer, ? extends CompletionStage<List<RTCIceServer>>> iceServers;
	final Consumer<RecoveryEvent> listener;

	private RecoveryPolicy(Builder b) {
		grace = b.grace;
		initialBackoff = b.initialBackoff;
		maxBackoff = b.maxBackoff;
		attemptTimeout = b.attemptTimeout;
		maxAttempts = b.maxAttempts;
		iceServers = b.iceServers;
		listener = b.listener;
	}

	public static Builder builder() { return new Builder(); }

	Duration backoff(int attempt) {
		long delay = Timeouts.toMillis(initialBackoff);
		long cap = Timeouts.toMillis(maxBackoff);
		for (int i = 1; i < attempt && delay < cap; i++) {
			delay = delay > cap / 2 ? cap : delay * 2;
		}
		return Duration.ofMillis(Math.min(delay, cap));
	}

	public static final class Builder {
		private Duration grace = Duration.ofSeconds(2);
		private Duration initialBackoff = Duration.ofSeconds(1);
		private Duration maxBackoff = Duration.ofSeconds(30);
		private Duration attemptTimeout = Duration.ofSeconds(15);
		private int maxAttempts = 3;
		private Function<Integer, ? extends CompletionStage<List<RTCIceServer>>> iceServers;
		private Consumer<RecoveryEvent> listener = event -> { };

		private Builder() { }

		/** Wait after DISCONNECTED; FAILED starts immediately. Default: two seconds. */
		public Builder grace(Duration value) {
			grace = Timeouts.requireNonNegative(value, "grace");
			return this;
		}

		/** Double the delay after each unsuccessful attempt, up to maximum. */
		public Builder backoff(Duration initial, Duration maximum) {
			Timeouts.requirePositive(initial, "initial backoff");
			Timeouts.requirePositive(maximum, "maximum backoff");
			if (initial.compareTo(maximum) > 0) {
				throw new IllegalArgumentException("Initial backoff exceeds maximum");
			}
			initialBackoff = initial;
			maxBackoff = maximum;
			return this;
		}

		/** Default: three attempts per outage; at least one. */
		public Builder maxAttempts(int value) {
			if (value < 1) throw new IllegalArgumentException("At least one attempt is required");
			maxAttempts = value;
			return this;
		}

		/**
		 * Default: 15 seconds, including credentials and waiting for CONNECTED.
		 * An HTTP restart already in flight must finish or reach its own operation
		 * timeout before the next attempt starts; exchanges never overlap.
		 */
		public Builder attemptTimeout(Duration value) {
			attemptTimeout = Timeouts.requirePositive(value, "attempt timeout");
			return this;
		}

		/**
		 * Before each attempt, asynchronously supplies the complete replacement
		 * list of ICE servers, including fresh TURN credentials. Called on the
		 * peer's event executor with the one-based attempt number; return promptly.
		 * A failed or null result fails the attempt. Late results after timeout,
		 * recovery or close are ignored. The provider owns cancellation of its I/O.
		 */
		public Builder iceServers(Function<Integer, ? extends CompletionStage<List<RTCIceServer>>> provider) {
			iceServers = requireNonNull(provider, "provider");
			return this;
		}

		/** Receives progress and exhaustion on the peer's event executor. */
		public Builder onEvent(Consumer<RecoveryEvent> handler) {
			listener = requireNonNull(handler, "handler");
			return this;
		}

		public RecoveryPolicy build() { return new RecoveryPolicy(this); }
	}
}
