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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * An executor whose tasks run only when a test runs them, on the test's thread,
 * so that the test decides how they interleave with everything else. Like a
 * pool, it rejects tasks once it is shut down, and still runs those queued
 * before; it can also be told to reject tasks while it runs.
 */
final class ManualExecutor extends AbstractExecutorService {

	private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

	private boolean shutdown;

	private boolean rejecting;

	private int running;


	@Override
	public synchronized void execute(Runnable task) {
		if (shutdown) {
			throw new RejectedExecutionException("Shut down");
		}
		if (rejecting) {
			throw new RejectedExecutionException("Rejecting");
		}

		queue.add(task);
		notifyAll();
	}

	/**
	 * @param rejecting true to reject tasks as a full pool does.
	 */
	synchronized void rejecting(boolean rejecting) {
		this.rejecting = rejecting;
	}

	synchronized int queued() {
		return queue.size();
	}

	/**
	 * Runs the oldest task queued.
	 *
	 * @return false if none was queued.
	 */
	boolean runNext() {
		Runnable task;

		synchronized (this) {
			task = queue.poll();

			if (task == null) {
				return false;
			}

			running++;
		}

		try {
			task.run();
		}
		finally {
			synchronized (this) {
				running--;
				notifyAll();
			}
		}

		return true;
	}

	/**
	 * Runs tasks until none is queued, including those they queue.
	 *
	 * @return the number of tasks run.
	 */
	int runAll() {
		int count = 0;

		while (runNext()) {
			count++;
		}

		return count;
	}

	/**
	 * Waits until a task is queued, which tells that another thread got as far
	 * as handing it over.
	 */
	synchronized void awaitQueued(long timeoutSeconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

		while (queue.isEmpty()) {
			long left = deadline - System.nanoTime();

			if (left <= 0) {
				throw new IllegalStateException("No task was queued within " + timeoutSeconds + " s");
			}

			TimeUnit.NANOSECONDS.timedWait(this, left);
		}
	}

	/**
	 * Runs tasks as they are queued until the condition holds. Other threads
	 * may queue them meanwhile.
	 */
	void runUntil(BooleanSupplier condition, long timeoutSeconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

		while (!condition.getAsBoolean()) {
			if (runNext()) {
				continue;
			}

			synchronized (this) {
				long left = deadline - System.nanoTime();

				if (left <= 0) {
					throw new IllegalStateException("The condition did not hold within " + timeoutSeconds + " s");
				}
				if (queue.isEmpty()) {
					// Woken by a new task; the condition may change without one.
					TimeUnit.NANOSECONDS.timedWait(this, Math.min(left, TimeUnit.MILLISECONDS.toNanos(10)));
				}
			}
		}
	}

	@Override
	public synchronized void shutdown() {
		shutdown = true;
		notifyAll();
	}

	@Override
	public synchronized List<Runnable> shutdownNow() {
		shutdown = true;

		List<Runnable> left = new ArrayList<>(queue);
		queue.clear();
		notifyAll();

		return left;
	}

	@Override
	public synchronized boolean isShutdown() {
		return shutdown;
	}

	@Override
	public synchronized boolean isTerminated() {
		return shutdown && queue.isEmpty() && running == 0;
	}

	@Override
	public synchronized boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		long deadline = System.nanoTime() + unit.toNanos(timeout);

		while (!isTerminated()) {
			long left = deadline - System.nanoTime();

			if (left <= 0) {
				return false;
			}

			TimeUnit.NANOSECONDS.timedWait(this, left);
		}

		return true;
	}
}
