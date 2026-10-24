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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.internal.Timeouts;

/**
 * A timer on which no time passes: what is scheduled fires only when a test
 * fires it, on the test's thread. Tasks are told apart by the delay they were
 * scheduled with, so that a timeout fires exactly when the test wants it to,
 * however long the work it bounds takes. A delay counts in milliseconds,
 * rounded up as the runtime rounds it.
 */
final class ManualTimer extends AbstractExecutorService implements ScheduledExecutorService {

	private final List<Task<?>> tasks = new ArrayList<>();

	private boolean shutdown;


	/**
	 * Fires every task scheduled with the delay that has not been cancelled.
	 * Periodic tasks stay scheduled, the others are done.
	 *
	 * @return the number of tasks fired.
	 */
	int fire(Duration delay) {
		List<Task<?>> due = new ArrayList<>();

		synchronized (this) {
			for (Task<?> task : tasks) {
				if (task.delayMillis == Timeouts.toMillis(delay) && !task.isDone()) {
					due.add(task);
				}
			}
			for (Task<?> task : due) {
				if (!task.periodic) {
					tasks.remove(task);
				}
			}
		}

		for (Task<?> task : due) {
			task.fire();
		}

		return due.size();
	}

	/**
	 * @return how many tasks with the delay wait to be fired.
	 */
	synchronized int scheduled(Duration delay) {
		int count = 0;

		for (Task<?> task : tasks) {
			if (task.delayMillis == Timeouts.toMillis(delay) && !task.isDone()) {
				count++;
			}
		}

		return count;
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
		return add(new Task<>(Executors.callable(command, null), unit.toMillis(delay), false));
	}

	@Override
	public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
		return add(new Task<>(callable, unit.toMillis(delay), false));
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
		return add(new Task<>(Executors.callable(command, null), unit.toMillis(period), true));
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
			TimeUnit unit) {
		return add(new Task<>(Executors.callable(command, null), unit.toMillis(delay), true));
	}

	@Override
	public void execute(Runnable command) {
		schedule(command, 0, TimeUnit.MILLISECONDS);
	}

	@Override
	public synchronized void shutdown() {
		shutdown = true;
	}

	@Override
	public synchronized List<Runnable> shutdownNow() {
		shutdown = true;

		List<Runnable> left = new ArrayList<>();

		for (Task<?> task : tasks) {
			task.cancel(false);
			left.add(task);
		}
		tasks.clear();

		return left;
	}

	@Override
	public synchronized boolean isShutdown() {
		return shutdown;
	}

	@Override
	public synchronized boolean isTerminated() {
		return shutdown;
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) {
		return isTerminated();
	}

	private synchronized <V> Task<V> add(Task<V> task) {
		if (shutdown) {
			throw new RejectedExecutionException("Shut down");
		}

		tasks.add(task);

		return task;
	}



	private static final class Task<V> extends FutureTask<V> implements ScheduledFuture<V> {

		final long delayMillis;

		final boolean periodic;


		Task(Callable<V> callable, long delayMillis, boolean periodic) {
			super(callable);

			this.delayMillis = delayMillis;
			this.periodic = periodic;
		}

		void fire() {
			if (periodic) {
				runAndReset();
			}
			else {
				run();
			}
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return unit.convert(delayMillis, TimeUnit.MILLISECONDS);
		}

		@Override
		public int compareTo(Delayed other) {
			return Long.compare(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS));
		}
	}
}
