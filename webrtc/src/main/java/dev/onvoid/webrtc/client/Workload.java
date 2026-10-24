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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * Counts the work of one kind that waits and runs, for {@link LoadStats}.
 * <p>
 * Each queue that holds work registers itself while it is not empty, so that
 * only the busy ones are looked at, and an idle one is not held on to.
 */
final class Workload {

	/** A queue of work. */
	interface Backlog {

		/**
		 * Adds what waits in the queue to the tally, under the queue's lock,
		 * so that its count and its oldest item fit together.
		 */
		void count(Tally tally);

	}

	private final LongSupplier clock;

	private final Set<Backlog> backlogs = ConcurrentHashMap.newKeySet();

	private final AtomicInteger active = new AtomicInteger();

	private final LongAdder dropped = new LongAdder();


	/**
	 * @param clock the time in nanoseconds, as {@link System#nanoTime()}.
	 */
	Workload(LongSupplier clock) {
		this.clock = clock;
	}

	long now() {
		return clock.getAsLong();
	}

	/** Called, under the queue's lock, when the queue was empty and got an item. */
	void waiting(Backlog backlog) {
		backlogs.add(backlog);
	}

	/** Called, under the queue's lock, when the queue became empty. */
	void idle(Backlog backlog) {
		backlogs.remove(backlog);
	}

	void started() {
		active.incrementAndGet();
	}

	void finished() {
		active.decrementAndGet();
	}

	void dropped(long count) {
		dropped.add(count);
	}

	LoadStats.Work snapshot() {
		Tally tally = new Tally();

		for (Backlog backlog : backlogs) {
			backlog.count(tally);
		}

		Duration oldestWaiting = tally.queued > 0
				? Duration.ofNanos(Math.max(0, now() - tally.oldest))
				: Duration.ZERO;

		return new LoadStats.Work(tally.queued, oldestWaiting, active.get(), dropped.sum());
	}



	/** What the busy queues hold, summed up. */
	static final class Tally {

		private int queued;

		private long oldest;


		/**
		 * @param waiting  the number of items a queue holds; one that went
		 *                 idle meanwhile adds none.
		 * @param queuedAt the clock time its oldest item was queued at.
		 */
		void add(int waiting, long queuedAt) {
			if (waiting <= 0) {
				return;
			}

			// Compared as a difference, as nanoTime values must be.
			oldest = queued == 0 || queuedAt - oldest < 0 ? queuedAt : oldest;
			queued += waiting;
		}
	}
}
