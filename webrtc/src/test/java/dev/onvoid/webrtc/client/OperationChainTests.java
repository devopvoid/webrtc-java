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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Drives an {@link OperationChain} on a control executor over a {@link
 * ManualExecutor}, with a {@link ManualTimer}, so that each test decides when
 * an operation times out. Needs no native code.
 */
class OperationChainTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private final List<Throwable> errors = new ArrayList<>();

	private final ManualExecutor pool = new ManualExecutor();

	private final ManualTimer timer = new ManualTimer();

	private final SerialExecutor control = new SerialExecutor(pool, errors::add, false, null);

	private final TestHost host = new TestHost();

	private final OperationChain chain = new OperationChain(control, timer, TIMEOUT, "the peer", host);

	private final List<String> started = new ArrayList<>();


	@AfterEach
	void noErrors() {
		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	@Test
	void runsOneAtATime() throws Exception {
		CompletableFuture<String> pending = new CompletableFuture<>();
		CompletableFuture<String> first = enqueue("first", () -> pending);
		CompletableFuture<String> second = enqueue("second", () -> CompletableFuture.completedFuture("done"));

		assertEquals(List.of("first"), started);

		run(() -> pending.complete("value"));

		assertEquals(List.of("first", "second"), started);
		assertEquals("value", first.get());
		assertEquals("done", second.get());
	}

	@Test
	void finishedRunsBeforeNext() throws Exception {
		AtomicReference<String> seen = new AtomicReference<>();
		CompletableFuture<String> first = onControl(() -> chain.enqueue(
				() -> CompletableFuture.completedFuture("value"), (value, error) -> {
					seen.set(value);
					throw new IllegalStateException("Cleanup failed");
				}));
		CompletableFuture<String> second = enqueue("second", () -> {
			assertEquals("value", seen.get());
			return CompletableFuture.completedFuture("done");
		});

		assertInstanceOf(IllegalStateException.class, failure(first));
		assertEquals("done", second.get());
	}

	@Test
	void throwingOperationFails() throws Exception {
		CompletableFuture<String> first = enqueue("first", () -> {
			throw new IllegalArgumentException("Refused");
		});
		CompletableFuture<String> second = enqueue("second", () -> CompletableFuture.completedFuture("done"));

		assertInstanceOf(IllegalArgumentException.class, failure(first));
		assertEquals("done", second.get());
	}

	@Test
	void timeoutRollsBackFirst() throws Exception {
		AtomicReference<CompletableFuture<String>> step = new AtomicReference<>();
		CompletableFuture<String> late = enqueue("late", () -> {
			step.set(chain.step());
			return step.get().thenApply(answer -> answer);
		});
		CompletableFuture<String> next = enqueue("next", () -> CompletableFuture.completedFuture("done"));
		CompletableFuture<Void> rollback = new CompletableFuture<>();
		host.rollback = () -> rollback;

		assertEquals(1, timer.fire(TIMEOUT));
		pool.runAll();

		// The step fails, so that an answer arriving late is not acted on.
		assertInstanceOf(TimeoutException.class, failure(step.get()));
		assertEquals(1, host.rollbacks);
		// The next one waits for signaling to be stable again.
		assertEquals(List.of("late"), started);
		assertFalse(late.isDone());

		run(() -> rollback.complete(null));

		assertInstanceOf(TimeoutException.class, failure(late));
		assertEquals(List.of("late", "next"), started);
		assertEquals("done", next.get());
		assertEquals(0, host.abandoned);
		assertEquals(0, timer.scheduled(TIMEOUT));
	}

	@Test
	void failedRollbackAbandons() {
		CompletableFuture<String> late = enqueue("late", CompletableFuture::new);
		NegotiationException refused = new NegotiationException("Rollback refused");
		host.rollback = () -> CompletableFuture.failedFuture(refused);

		assertEquals(1, timer.fire(TIMEOUT));
		pool.runAll();

		assertSame(refused, failure(late));
		assertEquals(1, host.abandoned);
	}

	@Test
	void stuckRollbackAbandons() {
		CompletableFuture<String> late = enqueue("late", CompletableFuture::new);
		CompletableFuture<String> next = enqueue("next", () -> CompletableFuture.completedFuture("done"));
		// A step of the operation, as the native rollback is.
		host.rollback = chain::step;

		assertEquals(1, timer.fire(TIMEOUT));
		pool.runAll();
		assertFalse(late.isDone());

		// The rollback does not finish within another timeout either.
		assertEquals(1, timer.fire(TIMEOUT));
		pool.runAll();

		assertEquals(1, host.abandoned);
		assertInstanceOf(PeerClosedException.class, failure(late));
		// The next one starts on a closed peer, and fails without running.
		assertInstanceOf(PeerClosedException.class, failure(next));
		assertEquals(List.of("late"), started);
	}

	@Test
	void closeFailsRunningAndQueued() {
		AtomicReference<CompletableFuture<String>> step = new AtomicReference<>();
		CompletableFuture<String> running = enqueue("running", () -> {
			step.set(chain.step());
			return step.get();
		});
		CompletableFuture<String> queued = enqueue("queued", () -> CompletableFuture.completedFuture("done"));

		run(host::abandon);

		assertInstanceOf(PeerClosedException.class, failure(step.get()));
		assertInstanceOf(PeerClosedException.class, failure(running));
		assertInstanceOf(PeerClosedException.class, failure(queued));
		assertEquals(List.of("running"), started);
		// Neither the closed operations nor the next ones roll back.
		assertEquals(0, host.rollbacks);
		assertEquals(0, timer.scheduled(TIMEOUT));
	}

	/**
	 * Enqueues an operation on the control executor, and runs the control
	 * tasks that follow from it.
	 */
	private <T> CompletableFuture<T> enqueue(String name, Supplier<CompletableFuture<T>> operation) {
		return onControl(() -> chain.enqueue(() -> {
			started.add(name);
			return operation.get();
		}));
	}

	private <T> T onControl(Supplier<T> task) {
		AtomicReference<T> result = new AtomicReference<>();

		control.execute(() -> result.set(task.get()));
		pool.runAll();

		return result.get();
	}

	private void run(Runnable task) {
		control.execute(task);
		pool.runAll();
	}

	private static Throwable failure(CompletableFuture<?> future) {
		assertTrue(future.isDone(), "Not done");

		return assertThrows(ExecutionException.class, future::get).getCause();
	}



	/** The peer the chain runs the operations of, as its teardown does. */
	private final class TestHost implements OperationChain.Host {

		Supplier<CompletableFuture<Void>> rollback = () -> CompletableFuture.completedFuture(null);

		int rollbacks;

		int abandoned;

		boolean closed;


		@Override
		public boolean isClosed() {
			return closed;
		}

		@Override
		public PeerClosedException closedError() {
			return new PeerClosedException("The peer is closed");
		}

		@Override
		public CompletableFuture<Void> rollback() {
			rollbacks++;

			return rollback.get();
		}

		@Override
		public void abandon() {
			abandoned++;
			closed = true;

			chain.close(closedError());
		}
	}
}
