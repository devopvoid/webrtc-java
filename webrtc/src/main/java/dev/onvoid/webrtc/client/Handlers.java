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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The handlers registered for one kind of event. They are called in the order
 * they were added, each in isolation: one that throws does not keep the others
 * from being called.
 *
 * @param <T> the event type.
 */
final class Handlers<T> {

	private final CopyOnWriteArrayList<Entry<T>> entries = new CopyOnWriteArrayList<>();

	private final Consumer<Throwable> errorHandler;


	Handlers(Consumer<Throwable> errorHandler) {
		this.errorHandler = errorHandler;
	}

	Subscription add(Consumer<? super T> handler) {
		// Wrapped, so that the same handler added twice is two subscriptions.
		Entry<T> entry = new Entry<>(handler);
		entries.add(entry);

		return () -> entries.remove(entry);
	}

	void accept(T value) {
		for (Entry<T> entry : entries) {
			try {
				entry.handler.accept(value);
			}
			catch (Throwable e) {
				errorHandler.accept(e);
			}
		}
	}

	boolean isEmpty() {
		return entries.isEmpty();
	}

	void clear() {
		entries.clear();
	}



	private static final class Entry<T> {

		final Consumer<? super T> handler;


		Entry(Consumer<? super T> handler) {
			this.handler = handler;
		}
	}
}
