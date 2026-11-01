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

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

/**
 * The sinks a track has registered in native code, each with the handle of
 * its native counterpart. Not API for applications.
 * <p>
 * Every method may be called from any thread at the same time. A sink is
 * registered in native code at most once, however many threads add it, and
 * {@link #close()} returns only once no native call made for a sink is still
 * running, so that the track can be freed after it.
 * <p>
 * The lock that guards the map is never held during a native call. WebRTC
 * holds a lock of its own while it hands media to the sinks, and adding or
 * removing a sink takes that lock too; a thread that waited for this lock
 * while holding WebRTC's would deadlock with one that held this lock while
 * waiting for WebRTC's.
 *
 * @param <S> the type of the sinks.
 */
public final class SinkRegistry<S> {

	private final Object lock = new Object();

	/** Guarded by {@link #lock}. */
	private final Map<S, Registration> registrations = new IdentityHashMap<>();

	private final LongConsumer nativeRemove;

	/** Native calls still running for this registry. Guarded by {@link #lock}. */
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
	 * Registers the sink unless it is registered already.
	 *
	 * @param sink      the sink.
	 * @param nativeAdd registers a native sink for it and returns its handle.
	 *
	 * @throws IllegalStateException if the registry has been closed.
	 */
	public void add(S sink, ToLongFunction<S> nativeAdd) {
		requireNonNull(sink);

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

		long handle;

		try {
			handle = nativeAdd.applyAsLong(sink);
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
	 * Unregisters the sink, if it is registered.
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

		release(registration);
	}

	/**
	 * Unregisters every sink and waits for the native calls still running
	 * for this registry. Adding a sink afterwards fails; closing it again
	 * does nothing.
	 */
	public void close() {
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



	private static final class Registration {

		/** The native sink, 0 while it is being added. Guarded by {@link #lock}. */
		long handle;

		/** Guarded by {@link #lock}. */
		boolean removed;

	}

}
