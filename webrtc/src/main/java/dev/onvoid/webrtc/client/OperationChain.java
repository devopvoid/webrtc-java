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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import dev.onvoid.webrtc.internal.Timeouts;

/**
 * The operations chain of a peer: runs each operation once the one before
 * finished, as the standard does with the changes it chains.
 * <p>
 * Everything here is confined to the control executor of the peer: an
 * operation is started there, and its result completes there. An operation
 * that WebRTC does not finish within the operation timeout fails with a
 * {@link TimeoutException}; the chain then has the host bring signaling back
 * to stable before the next operation starts, and gives the peer up when
 * that fails too, or does not finish within another timeout.
 */
final class OperationChain {

	/**
	 * What the chain needs of the peer it orders the operations of. Called
	 * on the control executor.
	 */
	interface Host {

		/**
		 * @return whether the peer is closed, so that no operation may run.
		 */
		boolean isClosed();

		/**
		 * @return the error that operations fail with once the peer closed.
		 */
		PeerClosedException closedError();

		/**
		 * Brings signaling back to stable after an operation timed out,
		 * through steps of that operation.
		 *
		 * @return a future that completes on the control executor.
		 */
		CompletableFuture<Void> rollback();

		/**
		 * Gives the peer up, once its signaling cannot be brought back to
		 * stable, and closes it.
		 */
		void abandon();
	}

	private final SerialExecutor control;

	private final ScheduledExecutorService timer;

	private final Duration timeout;

	private final String owner;

	private final Host host;

	private final ArrayDeque<Runnable> queued = new ArrayDeque<>();

	private boolean running;

	private CompletableFuture<?> runningOperation;

	/** The unfinished steps of the running operation. */
	private Operation activeOperation;


	/**
	 * @param control the control executor of the peer.
	 * @param timer   schedules the operation timeouts.
	 * @param timeout how long WebRTC may take for an operation.
	 * @param owner   names the peer in errors.
	 * @param host    the peer.
	 */
	OperationChain(SerialExecutor control, ScheduledExecutorService timer, Duration timeout, String owner,
			Host host) {
		this.control = control;
		this.timer = timer;
		this.timeout = timeout;
		this.owner = owner;
		this.host = host;
	}

	/**
	 * Runs an operation once the ones queued before it finished. It fails
	 * with a {@link PeerClosedException} when the peer closed first, and with
	 * a {@link TimeoutException} when WebRTC never finishes it.
	 *
	 * @return a future that completes on the control executor.
	 */
	<T> CompletableFuture<T> enqueue(Supplier<CompletableFuture<T>> operation) {
		return enqueue(operation, (value, error) -> { });
	}

	/**
	 * Runs an operation as {@link #enqueue(Supplier)} does.
	 *
	 * @param finished runs once the operation finished and signaling is back
	 *                 to stable, before the next operation starts; what it
	 *                 throws fails the result.
	 */
	<T> CompletableFuture<T> enqueue(Supplier<CompletableFuture<T>> operation, BiConsumer<T, Throwable> finished) {
		CompletableFuture<T> result = new CompletableFuture<>();

		queued.add(() -> start(operation, finished, result));

		runNext();

		return result;
	}

	/**
	 * Creates the future of a step of the running operation, such as a
	 * description WebRTC is applying, which the operation's timeout fails
	 * so that the stages depending on it cannot act on a late result.
	 *
	 * @return a future the caller completes on the control executor.
	 */
	<T> CompletableFuture<T> step() {
		CompletableFuture<T> future = new CompletableFuture<>();
		Operation scope = activeOperation;

		if (scope != null) {
			scope.steps.add(future);
			future.whenComplete((value, error) -> scope.steps.remove(future));
		}

		return future;
	}

	/**
	 * Fails the running operation and its unfinished steps, once the peer is
	 * closing. Those queued behind it fail as they start.
	 */
	void close(PeerClosedException error) {
		if (activeOperation != null) {
			for (CompletableFuture<?> step : new ArrayList<>(activeOperation.steps)) {
				step.completeExceptionally(error);
			}
		}
		if (runningOperation != null) {
			runningOperation.completeExceptionally(error);
		}
	}

	private <T> void start(Supplier<CompletableFuture<T>> operation, BiConsumer<T, Throwable> finished,
			CompletableFuture<T> result) {
		Operation scope = new Operation();
		activeOperation = scope;
		CompletableFuture<T> current;

		if (host.isClosed()) {
			current = CompletableFuture.failedFuture(host.closedError());
		}
		else {
			try {
				current = operation.get();
			}
			catch (Throwable e) {
				current = CompletableFuture.failedFuture(e);
			}
		}

		runningOperation = current;

		// One that is done already, as on a closed peer, needs no timeout.
		ScheduledFuture<?> timeout = current.isDone() ? null : timeOut(current, scope);

		current.whenComplete((value, error) -> control.execute(() -> {
			if (timeout != null) {
				timeout.cancel(false);
			}

			CompletableFuture<Void> stable = scope.expired && !host.isClosed()
					? host.rollback()
					: CompletableFuture.completedFuture(null);

			stable.whenComplete((ignored, cleanupError) -> control.execute(
					() -> finish(scope, value, cleanupError == null ? error : cleanupError, cleanupError != null,
							finished, result)));
		}));
	}

	private <T> void finish(Operation scope, T value, Throwable error, boolean unrecoverable,
			BiConsumer<T, Throwable> finished, CompletableFuture<T> result) {
		if (unrecoverable && !host.isClosed()) {
			// A peer whose signaling cannot be restored must not run another
			// negotiation against the abandoned description.
			host.abandon();
		}
		if (scope.cleanupTimeout != null) {
			scope.cleanupTimeout.cancel(false);
		}

		activeOperation = null;
		runningOperation = null;
		running = false;

		Throwable failure = error;

		try {
			finished.accept(value, failure);
		}
		catch (Throwable e) {
			failure = e;
		}

		PeerImpl.complete(result, value, failure);

		runNext();
	}

	/**
	 * Fails a running operation once the operation timeout has passed.
	 *
	 * @return the timeout, or null once the runtime has stopped its timer.
	 */
	private ScheduledFuture<?> timeOut(CompletableFuture<?> operation, Operation scope) {
		try {
			return timer.schedule(() -> control.execute(() -> expire(operation, scope)),
					Timeouts.toMillis(timeout), TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException e) {
			// The runtime is closing: control tasks queued before still run,
			// and it fails the futures they leave open.
			return null;
		}
	}

	private void expire(CompletableFuture<?> operation, Operation scope) {
		if (operation.isDone()) {
			return;
		}

		scope.expired = true;

		// Recovery calls are native operations too. If they stop answering,
		// close the peer instead of leaving its queue open.
		try {
			scope.cleanupTimeout = timer.schedule(() -> control.execute(() -> {
				if (activeOperation == scope && !host.isClosed()) {
					host.abandon();
				}
			}), Timeouts.toMillis(timeout), TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException e) {
			// Runtime shutdown closes the peer and its unfinished steps.
		}

		TimeoutException error = new TimeoutException("WebRTC did not finish an operation of "
				+ owner + " within " + timeout);

		// Fail the awaited steps, so dependent stages cannot apply a late
		// answer. Their error paths finish before the queue moves.
		List<CompletableFuture<?>> steps = new ArrayList<>(scope.steps);

		for (CompletableFuture<?> step : steps) {
			step.completeExceptionally(error);
		}
		if (steps.isEmpty()) {
			operation.completeExceptionally(error);
		}
	}

	private void runNext() {
		if (running) {
			return;
		}

		Runnable next = queued.poll();

		if (next != null) {
			running = true;
			next.run();
		}
	}



	/** The unfinished steps of an operation, and whether it timed out. */
	private static final class Operation {

		final Set<CompletableFuture<?>> steps = new HashSet<>();

		boolean expired;

		ScheduledFuture<?> cleanupTimeout;
	}
}
