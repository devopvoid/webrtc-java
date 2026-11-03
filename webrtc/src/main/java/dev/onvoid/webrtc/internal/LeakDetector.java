/*
 * Copyright 2019 Alex Andres
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

import dev.onvoid.webrtc.logging.Logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.Cleaner;
import java.util.function.Consumer;

/**
 * Reports native objects whose Java object became unreachable without having
 * been disposed or closed, which leaks the native object and whatever it
 * holds. Off by default; turned on with the system property {@value
 * #PROPERTY} set to {@code true} before the library is loaded. With {@value
 * #TRACE_PROPERTY} set to {@code true} as well, a report also tells where the
 * object was created.
 * <p>
 * Reports are warnings through {@link Logging}, which writes nothing unless
 * the application asks for it, e.g. with {@code
 * Logging.logToDebug(Logging.Severity.WARNING)} or a log sink. A report is
 * all that happens: freeing the native object on a garbage collector's
 * thread could free it under a WebRTC thread that still uses it.
 * <p>
 * Only objects that own a native object and are to be released with a
 * {@code dispose()} or {@code close()} method are watched, not views of an
 * object owned elsewhere nor reference counted ones. While off, nothing is
 * watched and nothing calls into Java for it.
 */
final class LeakDetector {

	/** The system property that turns the detector on. */
	static final String PROPERTY = "webrtc.java.leak.detection";

	/** The system property that adds where an object was created to its report. */
	static final String TRACE_PROPERTY = "webrtc.java.leak.trace";

	static final boolean ENABLED = Boolean.getBoolean(PROPERTY);

	private static final boolean TRACE = Boolean.getBoolean(TRACE_PROPERTY);

	/** Whether instances of a class are to be disposed or closed. */
	private static final ClassValue<Boolean> MUST_RELEASE = new ClassValue<>() {

		@Override
		protected Boolean computeValue(Class<?> type) {
			if (RefCounted.class.isAssignableFrom(type)) {
				return false;
			}

			return hasMethod(type, "dispose") || hasMethod(type, "close");
		}
	};

	/** Where reports go; tests take them. */
	static volatile Consumer<String> reporter = Logging::warn;

	private static volatile Cleaner cleaner;


	private LeakDetector() {

	}

	/**
	 * Turns the native side on when the detector is enabled. Called once the
	 * library is loaded.
	 */
	static void initialize() {
		if (ENABLED) {
			enableNative();
		}
	}

	/**
	 * Starts watching the object, which a native object has just been bound to.
	 *
	 * @return the record to mark released, or null if the object needs none.
	 */
	static Record track(NativeObject object) {
		Class<?> type = object.getClass();

		if (!MUST_RELEASE.get(type)) {
			return null;
		}

		Record record = new Record(type.getName(), TRACE ? new Throwable("Created here") : null);

		cleaner().register(object, record);

		return record;
	}

	private static Cleaner cleaner() {
		Cleaner current = cleaner;

		if (current == null) {
			synchronized (LeakDetector.class) {
				current = cleaner;

				if (current == null) {
					current = Cleaner.create(task -> {
						Thread thread = new Thread(task, "webrtc-leak-detector");
						thread.setDaemon(true);
						return thread;
					});

					cleaner = current;
				}
			}
		}

		return current;
	}

	private static boolean hasMethod(Class<?> type, String name) {
		try {
			type.getMethod(name);
			return true;
		}
		catch (NoSuchMethodException e) {
			return false;
		}
	}

	private static native void enableNative();



	/**
	 * What is known of a watched object once it is gone. Holds nothing of the
	 * object itself, which would keep it reachable.
	 */
	static final class Record implements Runnable {

		final String type;

		final Throwable origin;

		volatile boolean released;

		/** Whether the object is gone and this has been checked. */
		volatile boolean checked;


		Record(String type, Throwable origin) {
			this.type = type;
			this.origin = origin;
		}

		/** Runs on the cleaner's thread once the object is unreachable. */
		@Override
		public void run() {
			try {
				report();
			}
			finally {
				checked = true;
			}
		}

		private void report() {
			if (released) {
				return;
			}

			StringBuilder message = new StringBuilder()
					.append(type)
					.append(" was not disposed before it became unreachable; its native object leaks");

			if (origin != null) {
				StringWriter stack = new StringWriter();
				origin.printStackTrace(new PrintWriter(stack));

				message.append(System.lineSeparator()).append(stack);
			}

			try {
				reporter.accept(message.toString());
			}
			catch (RuntimeException | LinkageError e) {
				// A report must not end the cleaner's thread.
			}
		}
	}

}
