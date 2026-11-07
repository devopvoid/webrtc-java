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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.internal.Timeouts;

/** All state belongs to control. Application code is dispatched by the host. */
final class IceRecovery {
	interface Host {
		CompletableFuture<List<RTCIceServer>> servers(int attempt);
		void useServers(List<RTCIceServer> servers);
		void event(RecoveryEvent event);
	}

	private final RecoveryPolicy policy;
	private final Executor control;
	private final ScheduledExecutorService timer;
	private final BooleanSupplier closing;
	private final Supplier<CompletableFuture<Void>> restart;
	private final Host host;
	private ScheduledFuture<?> wait;
	private long generation;
	private int attempts;
	private boolean outage;
	private boolean exhausted;
	private boolean closed;
	private boolean exchanging;
	private boolean deferredAttempt;
	private boolean gracePeriod;
	private Throwable timedOut;

	IceRecovery(RecoveryPolicy policy, Executor control, ScheduledExecutorService timer,
			BooleanSupplier closing, Supplier<CompletableFuture<Void>> restart, Host host) {
		this.policy = policy;
		this.control = control;
		this.timer = timer;
		this.closing = closing;
		this.restart = restart;
		this.host = host;
	}

	void stateChanged(PeerState state) {
		if (closed || closing.getAsBoolean()) return;
		if (state == PeerState.CLOSED) { close(); return; }
		if (state == PeerState.CONNECTED) {
			if (outage) emit(RecoveryEvent.State.RECOVERED, null);
			outage = false;
			exhausted = false;
			attempts = 0;
			invalidate();
			return;
		}
		if (state != PeerState.DISCONNECTED && state != PeerState.FAILED) return;
		if (outage || exhausted) {
			if (state == PeerState.FAILED && gracePeriod && !exhausted) {
				invalidate();
				attempt();
			}
			return;
		}
		outage = true;
		if (state == PeerState.DISCONNECTED) {
			gracePeriod = true;
			emit(RecoveryEvent.State.WAITING, null);
			schedule(policy.grace, this::attempt);
		}
		else attempt();
	}

	void close() {
		closed = true;
		invalidate();
	}

	private void invalidate() {
		generation++;
		deferredAttempt = false;
		gracePeriod = false;
		if (wait != null) wait.cancel(false);
		wait = null;
		timedOut = null;
	}

	private boolean current(long token) {
		return token == generation && outage && !closed && !closing.getAsBoolean();
	}

	private void attempt() {
		if (closed || closing.getAsBoolean() || !outage || exhausted) return;
		// A connection can recover and fail again while an HTTP exchange ends.
		if (exchanging) { deferredAttempt = true; return; }
		gracePeriod = false;
		attempts++;
		long token = ++generation;
		emit(RecoveryEvent.State.ATTEMPTING, null);
		schedule(policy.attemptTimeout, () -> {
			Throwable error = new WebRtcException("ICE recovery attempt timed out");
			if (exchanging) timedOut = error;
			else failed(token, error);
		});
		try {
			host.servers(attempts).whenComplete((servers, error) -> control.execute(() -> {
				if (!current(token)) return;
				if (error != null) { failed(token, error); return; }
				try {
					if (servers != null) host.useServers(servers);
					exchanging = true;
					restart.get().whenComplete((ignored, failure) -> control.execute(() -> {
						exchanging = false;
						if (!current(token)) {
							if (deferredAttempt && outage && !exhausted && !closed && !closing.getAsBoolean()) {
								deferredAttempt = false;
								attempt();
							}
							return;
						}
						if (failure != null || timedOut != null) failed(token, failure != null ? failure : timedOut);
						// Successful signaling is not successful connectivity: keep the deadline.
					}));
				}
				catch (Throwable failure) { exchanging = false; failed(token, failure); }
			}));
		}
		catch (Throwable error) { failed(token, error); }
	}

	private void failed(long token, Throwable error) {
		if (!current(token)) return;
		invalidate();
		error = PeerImpl.unwrap(error);
		if (attempts >= policy.maxAttempts) {
			exhausted = true;
			emit(RecoveryEvent.State.EXHAUSTED, error);
		}
		else {
			emit(RecoveryEvent.State.RETRYING, error);
			schedule(policy.backoff(attempts), this::attempt);
		}
	}

	private void emit(RecoveryEvent.State state, Throwable cause) {
		host.event(new RecoveryEvent(state, attempts, cause));
	}

	private void schedule(Duration delay, Runnable task) {
		long token = generation;
		try {
			wait = timer.schedule(() -> control.execute(() -> {
				if (current(token)) { wait = null; task.run(); }
			}), Timeouts.toMillis(delay), TimeUnit.MILLISECONDS);
		}
		catch (java.util.concurrent.RejectedExecutionException error) {
			close(); // The runtime is stopping its timer.
		}
	}
}
