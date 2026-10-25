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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Runs tasks one at a time, in the order they were submitted, on threads of
 * another executor. Many of them share one pool, while the tasks of each run
 * as if on a thread of their own.
 * <p>
 * A task that throws does not stop the ones after it; its exception goes to
 * the error handler.
 * <p>
 * Each turn on the executor runs at most {@link #BATCH_SIZE} tasks and ends
 * once it has run for {@link #TURN_NANOS}, after which the executor queues
 * its next turn behind those of the others. A pool with a fixed number of
 * threads and a FIFO queue thus serves every executor in turn, however slow
 * the tasks of some of them are, and it holds at most one turn of each.
 * <p>
 * When the executor rejects the tasks, they are dropped, and those submitted
 * with a fallback have it run instead, on the thread that found them
 * rejected. A rejection by an executor that was not shut down goes to the
 * error handler as well, since it loses work the application expects.
 */
final class SerialExecutor implements Executor {

	/** Tasks run per turn, so one busy executor cannot starve the others of a shared pool. */
	static final int BATCH_SIZE = 64;

	/** How long a turn runs tasks before it gives the others of a shared pool a turn. */
	static final long TURN_NANOS = 10_000_000;

	/** Marks the threads that run event tasks, to catch a future waited on there. */
	private static final ThreadLocal<int[]> EVENT_DEPTH = new ThreadLocal<int[]>() {

		@Override
		protected int[] initialValue() {
			return new int[1];
		}
	};

	private final Executor executor;

	private final Consumer<Throwable> errorHandler;

	private final boolean eventThread;

	private final String threadName;

	private final Workload workload;

	private final ArrayDeque<Task> tasks = new ArrayDeque<>();

	private final Workload.Backlog backlog = this::count;

	private boolean scheduled;

	private volatile Thread runner;


	/**
	 * @param executor     the executor whose threads run the tasks.
	 * @param errorHandler receives what a task throws.
	 * @param eventThread  true when the tasks run application code, so that
	 *                     waiting on a future of this API there is refused.
	 * @param threadName   the name the running thread takes while it runs the
	 *                     tasks, or null to leave it alone.
	 */
	SerialExecutor(Executor executor, Consumer<Throwable> errorHandler,
			boolean eventThread, String threadName) {
		this(executor, new Workload(System::nanoTime), errorHandler, eventThread, threadName);
	}

	/**
	 * @param workload counts the tasks that wait and run, and those dropped
	 *                 when the executor rejects them while it runs.
	 */
	SerialExecutor(Executor executor, Workload workload, Consumer<Throwable> errorHandler,
			boolean eventThread, String threadName) {
		this.executor = executor;
		this.workload = workload;
		this.errorHandler = errorHandler;
		this.eventThread = eventThread;
		this.threadName = threadName;
	}

	/**
	 * @return true when the calling thread runs an event task right now, of
	 * any executor.
	 */
	static boolean onEventThread() {
		return EVENT_DEPTH.get()[0] > 0;
	}

	/**
	 * Runs the task, or the fallback should the executor reject it, which
	 * keeps a future the task would complete from staying open forever.
	 */
	void execute(Runnable task, Runnable dropped) {
		submit(new Task(task, dropped, workload.now()));
	}

	@Override
	public void execute(Runnable task) {
		submit(new Task(task, null, workload.now()));
	}

	private void submit(Task task) {
		synchronized (tasks) {
			if (tasks.isEmpty()) {
				workload.waiting(backlog);
			}

			tasks.add(task);

			if (scheduled) {
				return;
			}

			scheduled = true;
		}

		schedule();
	}

	/**
	 * @return true when the calling thread is running a task of this executor.
	 */
	boolean inExecutor() {
		return runner == Thread.currentThread();
	}

	private void schedule() {
		try {
			executor.execute(this::drain);
		}
		catch (RejectedExecutionException e) {
			List<Task> dropped;

			synchronized (tasks) {
				scheduled = false;
				dropped = new ArrayList<>(tasks);
				tasks.clear();
				workload.idle(backlog);
			}

			// The runtime's own pools reject only once it has stopped them,
			// and then nothing is lost that it does not fail itself.
			if (!(executor instanceof ExecutorService && ((ExecutorService) executor).isShutdown())) {
				workload.dropped(dropped.size());
				errorHandler.accept(new WebRtcException("The executor rejected " + dropped.size()
						+ " tasks, which were dropped", e));
			}

			// In order, as they would have run.
			for (Task task : dropped) {
				if (task.dropped != null) {
					try {
						task.dropped.run();
					}
					catch (Throwable error) {
						errorHandler.accept(error);
					}
				}
			}
		}
	}

	private void drain() {
		Thread thread = Thread.currentThread();
		String previousName = null;

		if (threadName != null) {
			previousName = thread.getName();
			thread.setName(threadName);
		}
		if (eventThread) {
			EVENT_DEPTH.get()[0]++;
		}

		runner = thread;

		boolean more = true;
		long start = workload.now();

		try {
			for (int i = 0; i < BATCH_SIZE && (i == 0 || workload.now() - start < TURN_NANOS); i++) {
				Task task;

				synchronized (tasks) {
					task = tasks.poll();

					if (task == null) {
						scheduled = false;
						more = false;
						break;
					}
					if (tasks.isEmpty()) {
						workload.idle(backlog);
					}
				}

				workload.started();

				try {
					task.task.run();
				}
				catch (Throwable e) {
					errorHandler.accept(e);
				}
				finally {
					workload.finished();
				}
			}
		}
		finally {
			runner = null;

			if (eventThread) {
				EVENT_DEPTH.get()[0]--;
			}
			if (previousName != null) {
				thread.setName(previousName);
			}
		}

		if (more) {
			// Give the other executors of the pool a turn.
			schedule();
		}
	}

	private void count(Workload.Tally tally) {
		synchronized (tasks) {
			Task oldest = tasks.peek();

			if (oldest != null) {
				tally.add(tasks.size(), oldest.queuedAt);
			}
		}
	}



	private static final class Task {

		final Runnable task;

		/** Runs instead of the task should the executor reject it, or null. */
		final Runnable dropped;

		final long queuedAt;


		Task(Runnable task, Runnable dropped, long queuedAt) {
			this.task = task;
			this.dropped = dropped;
			this.queuedAt = queuedAt;
		}
	}
}
