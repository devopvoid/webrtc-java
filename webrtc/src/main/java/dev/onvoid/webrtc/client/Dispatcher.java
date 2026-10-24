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
import java.util.function.Consumer;

/**
 * Hands the media of one subscription to its handler, in order, so that a
 * slow handler holds up neither WebRTC nor any other subscription.
 * <p>
 * Each item is a task of its own on the subscription's executor, so that a
 * handler that falls behind takes turns with the other subscriptions of a
 * shared pool instead of keeping a thread. When the waiting items weigh more
 * than the budget, the oldest are dropped; the newest one is always kept.
 *
 * @param <T> the media item.
 */
abstract class Dispatcher<T> {

	private final Consumer<T> handler;

	private final long budget;

	private final SerialExecutor executor;

	private final Workload workload;

	private final Consumer<Throwable> errorHandler;

	private final ArrayDeque<Entry<T>> queue = new ArrayDeque<>();

	private final Runnable next = this::handleNext;

	private final Workload.Backlog backlog = this::count;

	private long queuedWeight;

	private boolean scheduled;

	private boolean closed;


	/**
	 * @param budget   the weight of the items that may wait.
	 * @param workload counts the items that wait, run and are dropped.
	 */
	Dispatcher(Consumer<T> handler, long budget, SerialExecutor executor,
			Workload workload, Consumer<Throwable> errorHandler) {
		this.handler = handler;
		this.budget = budget;
		this.executor = executor;
		this.workload = workload;
		this.errorHandler = errorHandler;
	}

	/**
	 * @return how much of the budget the item takes.
	 */
	abstract long weight(T item);

	/**
	 * Lets go of an item once it was handled, dropped or left over at close.
	 */
	void release(T item) {
	}

	/**
	 * Queues an item for the handler, and drops the oldest ones over the
	 * budget.
	 */
	final void offer(T item) {
		boolean schedule = false;

		synchronized (queue) {
			if (closed) {
				release(item);
				return;
			}
			if (queue.isEmpty()) {
				workload.waiting(backlog);
			}

			Entry<T> entry = new Entry<>(item, weight(item), workload.now());

			queue.add(entry);
			queuedWeight += entry.weight;

			while (queuedWeight > budget && queue.size() > 1) {
				Entry<T> oldest = queue.poll();

				queuedWeight -= oldest.weight;
				workload.dropped(1);
				release(oldest.item);
			}

			if (!scheduled) {
				scheduled = true;
				schedule = true;
			}
		}

		if (schedule) {
			executor.execute(next);
		}
	}

	/**
	 * Drops what waits, and what comes later.
	 */
	final void close() {
		synchronized (queue) {
			closed = true;

			for (Entry<T> entry : queue) {
				release(entry.item);
			}

			queue.clear();
			queuedWeight = 0;
			workload.idle(backlog);
		}
	}

	private void handleNext() {
		Entry<T> entry;

		synchronized (queue) {
			entry = queue.poll();

			if (entry == null) {
				scheduled = false;
				return;
			}

			queuedWeight -= entry.weight;

			if (queue.isEmpty()) {
				workload.idle(backlog);
			}
		}

		workload.started();

		try {
			handler.accept(entry.item);
		}
		catch (Throwable e) {
			errorHandler.accept(e);
		}
		finally {
			workload.finished();
			release(entry.item);
		}

		boolean more;

		synchronized (queue) {
			more = !queue.isEmpty();

			if (!more) {
				scheduled = false;
			}
		}

		if (more) {
			// Behind what else the executor has, which ends its turn on the
			// pool once the turn has run long enough.
			executor.execute(next);
		}
	}

	private void count(Workload.Tally tally) {
		synchronized (queue) {
			Entry<T> oldest = queue.peek();

			if (oldest != null) {
				tally.add(queue.size(), oldest.queuedAt);
			}
		}
	}



	private static final class Entry<T> {

		final T item;

		final long weight;

		final long queuedAt;


		Entry(T item, long weight, long queuedAt) {
			this.item = item;
			this.weight = weight;
			this.queuedAt = queuedAt;
		}
	}
}
