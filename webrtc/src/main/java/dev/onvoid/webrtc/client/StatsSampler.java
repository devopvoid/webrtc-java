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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The statistics of a peer, read on request or at an interval.
 * <p>
 * The connection is asked and its report read on the control executor, which
 * alone uses the {@link StatsReader}. The timer only starts a sample, whose
 * result is handed to the event executor, where the handlers run.
 */
final class StatsSampler {

	private final PeerImpl peer;

	/** Confined to the control executor. */
	private final StatsReader reader = new StatsReader();


	StatsSampler(PeerImpl peer) {
		this.peer = peer;
	}

	/**
	 * @return a future with the statistics, which completes on the event
	 * executor.
	 */
	CompletableFuture<PeerStats> read() {
		return peer.toPublic(report());
	}

	/**
	 * @return a future with the statistics, which completes on the control
	 * executor.
	 */
	private CompletableFuture<PeerStats> report() {
		CompletableFuture<PeerStats> result = new CompletableFuture<>();

		peer.control.execute(() -> {
			if (peer.isClosed()) {
				result.completeExceptionally(peer.closedError());
				return;
			}

			try {
				peer.connection().getStats(report -> peer.control.execute(() -> {
					try {
						result.complete(reader.read(report, peer.pcmObservations()));
					}
					catch (Throwable e) {
						result.completeExceptionally(e);
					}
				}));
			}
			catch (Throwable e) {
				result.completeExceptionally(PeerImpl.asWebRtcException(e, "Reading the statistics failed"));
			}
		});

		return result;
	}

	/**
	 * Hands the handler the statistics at the given interval, until the
	 * subscription or the peer closes.
	 *
	 * @param interval the time between two samples.
	 * @param handler  gets each sample, on the event executor.
	 *
	 * @return the subscription of the handler.
	 */
	Subscription subscribe(Duration interval, Consumer<PeerStats> handler) {
		requireNonNull(interval, "interval");
		requireNonNull(handler, "handler");

		long millis = interval.toMillis();

		if (millis <= 0) {
			throw new IllegalArgumentException("The interval must be positive, got " + interval);
		}

		AtomicBoolean active = new AtomicBoolean(true);
		AtomicReference<ScheduledFuture<?>> task = new AtomicReference<>();

		Runnable sample = () -> {
			if (!active.get()) {
				return;
			}
			if (peer.isClosing()) {
				active.set(false);

				ScheduledFuture<?> scheduled = task.get();

				if (scheduled != null) {
					scheduled.cancel(false);
				}
				return;
			}

			// Handed over here: a callback on a public future runs on this
			// thread of the timer if the future is done by then.
			report().whenComplete((stats, error) -> peer.events.execute(() -> {
				if (!active.get()) {
					return;
				}
				if (error == null) {
					peer.call(handler, stats);
				}
				else if (!(PeerImpl.unwrap(error) instanceof PeerClosedException)) {
					peer.deliverError(PeerImpl.asWebRtcException(error, "Reading the statistics failed"));
				}
			}));
		};

		ScheduledFuture<?> scheduled = peer.rtc.timer.scheduleAtFixedRate(sample, 0, millis, TimeUnit.MILLISECONDS);

		task.set(scheduled);

		// The first sample is due at once, and may have found the peer closing
		// before it could see the task.
		if (!active.get()) {
			scheduled.cancel(false);
		}

		return () -> {
			active.set(false);
			task.get().cancel(false);
		};
	}
}
