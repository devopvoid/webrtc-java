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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Drives a {@link SerialExecutor} through a {@link ManualExecutor}, so that
 * each test decides when the pool runs a task and when it rejects one, and
 * through a clock that moves only when the test moves it.
 */
class SerialExecutorTests {

	private final List<Throwable> errors = new ArrayList<>();

	private final ManualExecutor pool = new ManualExecutor();

	private long now;

	private final Workload work = new Workload(() -> now);

	private final SerialExecutor serial = new SerialExecutor(pool, work, errors::add, false, null);


	@Test
	void rejectionRunsFallback() {
		List<String> ran = new ArrayList<>();
		SerialExecutor refused = new SerialExecutor(task -> {
			throw new RejectedExecutionException("Full");
		}, errors::add, false, null);

		refused.execute(() -> ran.add("task"), () -> ran.add("fallback on " + Thread.currentThread().getName()));

		// On the thread that found the task rejected, before execute returns.
		assertEquals(Collections.singletonList("fallback on " + Thread.currentThread().getName()), ran);
		assertEquals(1, errors.size(), () -> "Errors: " + errors);
		assertInstanceOf(WebRtcException.class, errors.get(0));
		assertInstanceOf(RejectedExecutionException.class, errors.get(0).getCause());
	}

	@Test
	void shutdownRejectsQuietly() {
		List<String> ran = new ArrayList<>();
		pool.shutdown();

		serial.execute(() -> ran.add("task"), () -> ran.add("fallback"));
		serial.execute(() -> ran.add("plain"));

		// The pool's owner shut it down and fails what it loses itself.
		assertEquals(Arrays.asList("fallback"), ran);
		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
		assertEquals(0, work.snapshot().dropped());
		assertEquals(0, work.snapshot().queued());
	}

	@Test
	void rejectedTurnDropsRest() {
		List<Integer> ran = new ArrayList<>();
		List<Integer> dropped = new ArrayList<>();
		int count = 70;

		for (int i = 0; i < count; i++) {
			int n = i;

			if (n == 66) {
				// One without a fallback is dropped as well, and counted.
				serial.execute(() -> ran.add(n));
				continue;
			}
			serial.execute(() -> ran.add(n), () -> dropped.add(n));
		}

		// One turn on the pool for all of them.
		assertEquals(1, pool.queued());

		// The pool fills up while the first batch runs, so the next turn is refused.
		pool.rejecting(true);
		pool.runNext();

		assertEquals(range(0, 64), ran);
		assertEquals(Arrays.asList(64, 65, 67, 68, 69), dropped);
		assertEquals(1, errors.size(), () -> "Errors: " + errors);
		assertTrue(errors.get(0).getMessage().contains("rejected 6 tasks"), errors.get(0).getMessage());
		assertEquals(6, work.snapshot().dropped());
		assertEquals(0, work.snapshot().queued());

		// Taken again once the pool accepts work.
		pool.rejecting(false);
		serial.execute(() -> ran.add(100));
		pool.runAll();

		assertEquals(100, (int) ran.get(ran.size() - 1));
	}

	@Test
	void throwingFallbackReported() {
		List<Integer> dropped = new ArrayList<>();
		IllegalStateException failure = new IllegalStateException("fallback failed");

		for (int i = 0; i < 64; i++) {
			serial.execute(() -> { });
		}
		serial.execute(() -> { }, () -> {
			throw failure;
		});
		serial.execute(() -> { }, () -> dropped.add(1));

		pool.rejecting(true);
		pool.runNext();

		// The fallback after the one that threw still runs.
		assertEquals(Collections.singletonList(1), dropped);
		assertEquals(2, errors.size(), () -> "Errors: " + errors);
		assertSame(failure, errors.get(1));
	}

	@Test
	void batchesShareThePool() {
		List<String> ran = new ArrayList<>();
		SerialExecutor other = new SerialExecutor(pool, work, errors::add, false, null);

		for (int i = 0; i < 100; i++) {
			int n = i;
			serial.execute(() -> ran.add("serial-" + n));
		}
		other.execute(() -> ran.add("other"));

		pool.runNext();
		pool.runNext();
		pool.runNext();

		// The other executor's task runs after the first batch, not after all.
		assertEquals("serial-63", ran.get(63));
		assertEquals("other", ran.get(64));
		assertEquals("serial-64", ran.get(65));
		assertEquals(101, ran.size());
		assertEquals(0, pool.queued());
	}

	@Test
	void turnEndsAfterTimeSlice() {
		List<String> ran = new ArrayList<>();
		SerialExecutor other = new SerialExecutor(pool, work, errors::add, false, null);
		long slow = SerialExecutor.TURN_NANOS * 6 / 10;

		for (int i = 0; i < 3; i++) {
			int n = i;
			serial.execute(() -> {
				ran.add("slow-" + n);
				now += slow;
			});
		}
		other.execute(() -> ran.add("other"));

		pool.runAll();

		// Two slow tasks fill a turn, far below the batch size.
		assertEquals(Arrays.asList("slow-0", "slow-1", "other", "slow-2"), ran);
	}

	@Test
	void loadCounted() {
		List<LoadStats.Work> seen = new ArrayList<>();
		SerialExecutor other = new SerialExecutor(pool, work, errors::add, false, null);

		serial.execute(() -> seen.add(work.snapshot()));
		now += 5_000_000;
		serial.execute(() -> { });
		other.execute(() -> { });
		now += 3_000_000;

		LoadStats.Work before = work.snapshot();

		assertEquals(3, before.queued());
		assertEquals(Duration.ofMillis(8), before.oldestWaiting());
		assertEquals(0, before.active());

		pool.runAll();

		// While the first task ran: the other two waited, the older since 5 ms.
		LoadStats.Work during = seen.get(0);

		assertEquals(2, during.queued());
		assertEquals(Duration.ofMillis(3), during.oldestWaiting());
		assertEquals(1, during.active());

		LoadStats.Work after = work.snapshot();

		assertEquals(0, after.queued());
		assertEquals(Duration.ZERO, after.oldestWaiting());
		assertEquals(0, after.active());
		assertEquals(0, after.dropped());
	}

	@Test
	void throwingTaskReported() {
		List<String> ran = new ArrayList<>();
		IllegalStateException failure = new IllegalStateException("task failed");

		serial.execute(() -> {
			throw failure;
		});
		serial.execute(() -> ran.add("next"));
		pool.runAll();

		assertEquals(Collections.singletonList("next"), ran);
		assertEquals(Collections.singletonList(failure), errors);
	}

	@Test
	void throwingHandlerKeepsTasks() {
		List<String> ran = new ArrayList<>();
		IllegalStateException failure = new IllegalStateException("handler failed");
		SerialExecutor failing = new SerialExecutor(pool, work, error -> {
			throw failure;
		}, false, null);

		List<Throwable> uncaught = whileUncaughtHandlerThrows(() -> {
			failing.execute(() -> {
				throw new IllegalStateException("task failed");
			});
			failing.execute(() -> ran.add("next"));
			pool.runAll();

			// Still able to start a turn of its own.
			failing.execute(() -> ran.add("later"));
			pool.runAll();
		});

		assertEquals(Arrays.asList("next", "later"), ran);
		assertEquals(Collections.singletonList(failure), uncaught);
		assertEquals(0, work.snapshot().queued());
		assertEquals(0, work.snapshot().active());
	}

	@Test
	void throwingHandlerKeepsFallbacks() {
		List<Integer> dropped = new ArrayList<>();
		SerialExecutor failing = new SerialExecutor(pool, work, error -> {
			throw new IllegalStateException("handler failed");
		}, false, null);

		whileUncaughtHandlerThrows(() -> {
			for (int i = 0; i < SerialExecutor.BATCH_SIZE; i++) {
				failing.execute(() -> { });
			}
			failing.execute(() -> { }, () -> {
				dropped.add(1);
				throw new IllegalStateException("fallback failed");
			});
			failing.execute(() -> { }, () -> dropped.add(2));

			pool.rejecting(true);
			pool.runNext();
		});

		// The futures these complete must not stay open.
		assertEquals(Arrays.asList(1, 2), dropped);
	}

	@Test
	void abortedTurnSchedulesNext() {
		List<String> ran = new ArrayList<>();
		IllegalStateException failure = new IllegalStateException("clock failed");
		boolean[] clockFails = new boolean[1];
		Workload workload = new Workload(() -> {
			if (clockFails[0]) {
				throw failure;
			}
			return 0;
		});
		SerialExecutor aborted = new SerialExecutor(pool, workload, errors::add, false, null);

		aborted.execute(() -> {
			ran.add("first");
			clockFails[0] = true;
		});
		aborted.execute(() -> ran.add("second"));
		pool.runNext();

		assertEquals(Collections.singletonList("first"), ran);
		assertEquals(Collections.singletonList(failure), errors);
		assertEquals(1, pool.queued());

		clockFails[0] = false;
		pool.runAll();

		assertEquals(Arrays.asList("first", "second"), ran);
	}

	@Test
	void failingExecutorRunsFallback() {
		List<String> ran = new ArrayList<>();
		IllegalStateException failure = new IllegalStateException("Toolkit gone");
		boolean[] executorFails = { true };
		SerialExecutor failing = new SerialExecutor(task -> {
			if (executorFails[0]) {
				throw failure;
			}
			pool.execute(task);
		}, work, errors::add, false, null);

		failing.execute(() -> ran.add("task"), () -> ran.add("fallback"));

		assertEquals(Collections.singletonList("fallback"), ran);
		assertEquals(1, errors.size(), () -> "Errors: " + errors);
		assertSame(failure, errors.get(0).getCause());
		assertEquals(1, work.snapshot().dropped());
		assertEquals(0, work.snapshot().queued());

		// The next task tries the executor again.
		executorFails[0] = false;
		failing.execute(() -> ran.add("later"));
		pool.runAll();

		assertEquals(Arrays.asList("fallback", "later"), ran);
	}

	@Test
	void idleCleanupCannotClearNextThreadsMarker() throws Exception {
		CountDownLatch cleaning = new CountDownLatch(1);
		CountDownLatch finishCleanup = new CountDownLatch(1);
		CountDownLatch secondStarted = new CountDownLatch(1);
		CountDownLatch checkMarker = new CountDownLatch(1);
		AtomicBoolean holdCleanup = new AtomicBoolean(true);
		AtomicBoolean marked = new AtomicBoolean();
		ThreadLocal<Boolean> running = new ThreadLocal<Boolean>() {
			@Override
			public void remove() {
				if (holdCleanup.compareAndSet(true, false)) {
					// The queue is idle and another thread may start its turn,
					// but this turn has not yet cleared its executor marker.
					cleaning.countDown();
					awaitLatch(finishCleanup);
				}
				super.remove();
			}
		};
		SerialExecutor events = new SerialExecutor(pool, work, errors::add, true, null, running);
		Thread first = new Thread(pool::runNext, "first-turn");
		Thread second = new Thread(pool::runNext, "second-turn");

		try {
			events.execute(() -> assertTrue(events.inExecutor()));
			first.start();
			awaitLatch(cleaning);

			events.execute(() -> {
				secondStarted.countDown();
				awaitLatch(checkMarker);
				marked.set(events.inExecutor());
				assertTrue(SerialExecutor.onEventThread());
			});
			second.start();
			awaitLatch(secondStarted);

			// Complete A's cleanup while B is still inside its event task.
			finishCleanup.countDown();
			first.join(5000);
			assertFalse(first.isAlive());
			checkMarker.countDown();
			second.join(5000);
			assertFalse(second.isAlive());

			assertTrue(marked.get());
			assertFalse(events.inExecutor());
			assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
		}
		finally {
			finishCleanup.countDown();
			checkMarker.countDown();
			first.join(5000);
			second.join(5000);
		}
	}

	private static void awaitLatch(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for the other turn");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	@Test
	void eventThreadMarked() {
		SerialExecutor events = new SerialExecutor(pool, errors::add, true, "events-test");
		List<Object> seen = new ArrayList<>();
		String name = Thread.currentThread().getName();

		events.execute(() -> {
			seen.add(SerialExecutor.onEventThread());
			seen.add(events.inExecutor());
			seen.add(serial.inExecutor());
			seen.add(Thread.currentThread().getName());
		});
		pool.runAll();

		assertEquals(Arrays.asList(true, true, false, "events-test"), seen);
		assertFalse(SerialExecutor.onEventThread());
		assertFalse(events.inExecutor());
		assertEquals(name, Thread.currentThread().getName());
		assertTrue(errors.isEmpty(), () -> "Errors: " + errors);
	}

	/**
	 * Runs the action while the uncaught exception handler of this thread
	 * throws, as the handler of an application may.
	 *
	 * @return what the handler received.
	 */
	private static List<Throwable> whileUncaughtHandlerThrows(Runnable action) {
		List<Throwable> uncaught = new ArrayList<>();
		Thread thread = Thread.currentThread();
		Thread.UncaughtExceptionHandler previous = thread.getUncaughtExceptionHandler();

		thread.setUncaughtExceptionHandler((failed, error) -> {
			uncaught.add(error);
			throw new IllegalStateException("uncaught exception handler failed");
		});

		try {
			action.run();
		}
		finally {
			thread.setUncaughtExceptionHandler(previous);
		}

		return uncaught;
	}

	private static List<Integer> range(int from, int to) {
		return IntStream.range(from, to).boxed().collect(Collectors.toList());
	}

}
