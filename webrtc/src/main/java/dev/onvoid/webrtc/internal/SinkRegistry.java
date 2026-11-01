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

package dev.onvoid.webrtc.internal;

import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.logging.Logging;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/**
 * The sinks a track has registered in native code, each with the handle of
 * its native counterpart. Not API for applications.
 * <p>
 * Every method may be called from any thread at the same time. A sink is
 * registered in native code at most once, however many threads add it, and
 * {@link #close()} returns only once no native call made for a sink is still
 * running, so that the track can be freed after it.
 * <p>
 * WebRTC holds a lock while it hands media to the sinks of a source, and
 * adding or removing a sink takes that lock too, on another thread that the
 * calling one waits for. A sink that adds or removes a sink from within its
 * callback would wait for itself. So the native sink of the track wraps the
 * sink of the application and tells this registry, through its {@link
 * Registration}, when a thread is handing it media: a change made on such a
 * thread takes effect at once for the Java side, a removed sink is not called
 * again, and the native call follows on a thread of its own once the callback
 * has returned.
 * <p>
 * For the same reason the lock that guards the map is never held during a
 * native call: a thread that waited for it while holding WebRTC's lock would
 * deadlock with one that held it while waiting for WebRTC's.
 *
 * @param <S> the type of the sinks.
 */
public final class SinkRegistry<S> {

	/** How many callbacks of sinks the current thread is in. */
	private static final ThreadLocal<int[]> CALLBACK_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

	private final Object lock = new Object();

	/** Guarded by {@link #lock}. */
	private final Map<S, Registration> registrations = new IdentityHashMap<>();

	private final LongConsumer nativeRemove;

	/** Native calls running or deferred for this registry. Guarded by {@link #lock}. */
	private int running;

	/** Guarded by {@link #lock}. */
	private boolean closed;


	/**
	 * @param nativeRemove unregisters the native sink of a handle and frees it.
	 */
	public SinkRegistry(LongConsumer nativeRemove) {
		this.nativeRemove = requireNonNull(nativeRemove);
	}

	/**
	 * Registers the sink unless it is registered already. Called from within
	 * the callback of a sink, it returns before the native sink is registered,
	 * which happens on another thread right after.
	 *
	 * @param sink      the sink.
	 * @param nativeAdd registers a native sink for it and returns its handle.
	 *
	 * @throws IllegalStateException if the registry has been closed.
	 */
	public void add(S sink, NativeAdd<S> nativeAdd) {
		requireNonNull(sink);
		requireNonNull(nativeAdd);

		Registration registration = new Registration();

		synchronized (lock) {
			if (closed) {
				throw new IllegalStateException("The track has been disposed");
			}
			if (registrations.containsKey(sink)) {
				return;
			}

			// Taken before the native call, so that a second add of the same
			// sink finds it and does not register it again.
			registrations.put(sink, registration);
			running++;
		}

		if (inCallback()) {
			Deferred.run(() -> register(sink, registration, nativeAdd));
		}
		else {
			register(sink, registration, nativeAdd);
		}
	}

	/**
	 * Unregisters the sink, if it is registered. Once this returns, the sink
	 * is not called again, except by a callback that another thread is
	 * running at that moment. Called from within the callback of a sink, the
	 * native sink is unregistered on another thread right after.
	 *
	 * @param sink the sink.
	 */
	public void remove(S sink) {
		requireNonNull(sink);

		Registration registration;

		synchronized (lock) {
			registration = registrations.remove(sink);

			if (registration == null || !take(registration)) {
				return;
			}
		}

		if (inCallback()) {
			Deferred.run(() -> release(registration));
		}
		else {
			release(registration);
		}
	}

	/**
	 * Unregisters every sink and waits for the native calls still running or
	 * deferred for this registry. Adding a sink afterwards fails; closing it
	 * again does nothing.
	 *
	 * @throws IllegalStateException if called from within the callback of a
	 *                               sink, which would wait for itself.
	 */
	public void close() {
		if (inCallback()) {
			throw new IllegalStateException("A track cannot be disposed on a thread that is handing media "
					+ "to a sink. Remove the sink there, and dispose the track on another thread");
		}

		List<Registration> taken = new ArrayList<>();

		synchronized (lock) {
			closed = true;

			for (Registration registration : registrations.values()) {
				if (take(registration)) {
					taken.add(registration);
				}
			}

			registrations.clear();
		}

		RuntimeException failure = null;

		for (Registration registration : taken) {
			try {
				release(registration);
			}
			catch (RuntimeException e) {
				if (failure == null) {
					failure = e;
				}
			}
		}

		awaitNativeCalls();

		if (failure != null) {
			throw failure;
		}
	}

	/**
	 * Waits until the native calls deferred so far, by any registry, have
	 * been made. For tests.
	 */
	public static void awaitDeferred() throws InterruptedException {
		CountDownLatch done = new CountDownLatch(1);

		Deferred.run(done::countDown);

		done.await();
	}

	private static boolean inCallback() {
		return CALLBACK_DEPTH.get()[0] > 0;
	}

	private void register(S sink, Registration registration, NativeAdd<S> nativeAdd) {
		synchronized (lock) {
			if (registration.removed) {
				// Removed before a deferred add got to it.
				finished();
				return;
			}
		}

		long handle;

		try {
			handle = nativeAdd.add(sink, registration);
		}
		catch (RuntimeException | Error e) {
			synchronized (lock) {
				registrations.remove(sink, registration);
				registration.removed = true;
				finished();
			}
			throw e;
		}

		boolean removed;

		synchronized (lock) {
			registration.handle = handle;
			removed = registration.removed;

			if (!removed) {
				finished();
			}
		}

		if (removed) {
			// Removed, or the registry closed, while it was being added: the
			// remover left the native sink to this thread.
			release(registration);
		}
	}

	/**
	 * Marks a registration removed. Returns whether the caller is to release
	 * its native sink, which it is only once the native add has returned; the
	 * adder releases it otherwise. Called with {@link #lock} held.
	 */
	private boolean take(Registration registration) {
		registration.removed = true;

		if (registration.handle == 0) {
			return false;
		}

		running++;
		return true;
	}

	private void release(Registration registration) {
		try {
			nativeRemove.accept(registration.handle);
		}
		finally {
			synchronized (lock) {
				finished();
			}
		}
	}

	/** Called with {@link #lock} held. */
	private void finished() {
		if (--running == 0) {
			lock.notifyAll();
		}
	}

	private void awaitNativeCalls() {
		boolean interrupted = false;

		synchronized (lock) {
			while (running > 0) {
				try {
					lock.wait();
				}
				catch (InterruptedException e) {
					interrupted = true;
				}
			}
		}

		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}



	/**
	 * Registers the native sink of a sink.
	 *
	 * @param <S> the type of the sinks.
	 */
	@FunctionalInterface
	public interface NativeAdd<S> {

		/**
		 * @param sink         the sink of the application.
		 * @param registration what the native sink has to {@link
		 *                     Registration#enter() enter} before it calls the
		 *                     sink.
		 *
		 * @return the handle of the native sink.
		 */
		long add(S sink, Registration registration);

	}



	/**
	 * The registration of one sink, which the code that calls the sink tells
	 * when it does.
	 */
	public static final class Registration {

		/** The native sink, 0 while it is being added. Guarded by the registry's lock. */
		private long handle;

		/** Written with the registry's lock held, read without it by {@link #enter()}. */
		private volatile boolean removed;


		private Registration() {

		}

		/**
		 * Called before the sink is handed media, on the thread that hands it
		 * over. Returns false, and the media is not to be handed over, once
		 * the sink has been removed; otherwise {@link #exit()} must follow
		 * once the sink returned.
		 *
		 * @return whether to call the sink.
		 */
		public boolean enter() {
			if (removed) {
				return false;
			}

			CALLBACK_DEPTH.get()[0]++;
			return true;
		}

		/**
		 * Called once the sink returned from a call that {@link #enter()}
		 * allowed.
		 */
		public void exit() {
			CALLBACK_DEPTH.get()[0]--;
		}

	}



	/**
	 * Makes the native calls that a callback asked for, one after another,
	 * on a thread that never hands media to a sink.
	 */
	private static final class Deferred {

		private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), runnable -> {
					Thread thread = new Thread(runnable, "webrtc-java-sink-changes");
					thread.setDaemon(true);
					return thread;
				});


		static void run(Runnable change) {
			EXECUTOR.execute(() -> {
				try {
					change.run();
				}
				catch (Throwable e) {
					// No caller is left to hand it to.
					Logging.error("A sink change made in a sink callback failed", e);
				}
			});
		}

	}

}
