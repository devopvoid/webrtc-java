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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Runs tasks one at a time, in the order they were submitted, on threads of
 * another executor. Many of them share one pool, while the tasks of each run
 * as if on a thread of their own.
 * <p>
 * A task that throws does not stop the ones after it; its exception goes to
 * the error handler.
 */
final class SerialExecutor implements Executor {

	/** Tasks run per turn, so one busy executor cannot starve the others of a shared pool. */
	private static final int BATCH_SIZE = 64;

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

	private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

	private boolean scheduled;

	private boolean shutdown;

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
		this.executor = executor;
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

	@Override
	public void execute(Runnable task) {
		synchronized (tasks) {
			if (shutdown) {
				return;
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

	/**
	 * Drops the tasks not yet run and refuses new ones. A task running now
	 * finishes.
	 */
	void shutdown() {
		synchronized (tasks) {
			shutdown = true;
			tasks.clear();
		}
	}

	private void schedule() {
		try {
			executor.execute(this::drain);
		}
		catch (RejectedExecutionException e) {
			synchronized (tasks) {
				scheduled = false;
				tasks.clear();
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

		try {
			for (int i = 0; i < BATCH_SIZE; i++) {
				Runnable task;

				synchronized (tasks) {
					task = tasks.poll();

					if (task == null) {
						scheduled = false;
						more = false;
						break;
					}
				}

				try {
					task.run();
				}
				catch (Throwable e) {
					errorHandler.accept(e);
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
}
