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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

import org.junit.jupiter.api.Test;

class SinkRegistryTests {

	/** What the native side was asked to do, in order. */
	private final List<String> calls = new CopyOnWriteArrayList<>();

	private final SinkRegistry<Object> registry = new SinkRegistry<>(handle -> calls.add("remove " + handle));

	private final AtomicInteger nextHandle = new AtomicInteger();


	@Test
	void secondAddDoesNothing() {
		Object sink = new Object();

		registry.add(sink, add());
		registry.add(sink, add());

		assertEquals(List.of("add 1"), calls);
	}

	@Test
	void concurrentAddsRegisterOnce() throws Exception {
		Object sink = new Object();
		BlockedAdd blocked = new BlockedAdd();

		CompletableFuture<Void> first = CompletableFuture.runAsync(() -> registry.add(sink, blocked));
		blocked.awaitEntered();

		// The first add is still in its native call, and this one must not
		// make a second registration while it is.
		registry.add(sink, add());

		blocked.proceed();
		first.get(5, TimeUnit.SECONDS);

		assertEquals(List.of("add 1"), calls);

		registry.remove(sink);

		assertEquals(List.of("add 1", "remove 1"), calls);
	}

	@Test
	void removeDuringAddReleasesOnce() throws Exception {
		Object sink = new Object();
		BlockedAdd blocked = new BlockedAdd();

		CompletableFuture<Void> adding = CompletableFuture.runAsync(() -> registry.add(sink, blocked));
		blocked.awaitEntered();

		// No handle yet, so the adder releases the native sink once it has one.
		registry.remove(sink);
		registry.remove(sink);

		assertEquals(Collections.emptyList(), calls);

		blocked.proceed();
		adding.get(5, TimeUnit.SECONDS);

		assertEquals(List.of("add 1", "remove 1"), calls);

		// Registered anew, with a native sink of its own.
		registry.add(sink, add());

		assertEquals(List.of("add 1", "remove 1", "add 2"), calls);
	}

	@Test
	void closeWaitsForAdd() throws Exception {
		Object sink = new Object();
		BlockedAdd blocked = new BlockedAdd();

		CompletableFuture<Void> adding = CompletableFuture.runAsync(() -> registry.add(sink, blocked));
		blocked.awaitEntered();

		CompletableFuture<Void> closing = CompletableFuture.runAsync(() -> {
			registry.close();
			calls.add("closed");
		});

		blocked.proceed();
		adding.get(5, TimeUnit.SECONDS);
		closing.get(5, TimeUnit.SECONDS);

		// The track is freed after close returns, so no native call may still
		// be running then.
		assertEquals(List.of("add 1", "remove 1", "closed"), calls);
	}

	@Test
	void closeRemovesAll() {
		registry.add(new Object(), add());
		registry.add(new Object(), add());

		registry.close();
		registry.close();

		assertEquals(4, calls.size());
		assertTrue(calls.containsAll(List.of("remove 1", "remove 2")));
	}

	@Test
	void addAfterCloseFails() {
		registry.close();

		assertThrows(IllegalStateException.class, () -> registry.add(new Object(), add()));
		assertEquals(Collections.emptyList(), calls);
	}

	@Test
	void failedAddLeavesNothing() {
		Object sink = new Object();
		RuntimeException failure = new RuntimeException("Not added");

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> registry.add(sink, s -> {
			throw failure;
		}));

		assertSame(failure, thrown);

		registry.add(sink, add());
		registry.close();

		assertEquals(List.of("add 1", "remove 1"), calls);
	}

	private ToLongFunction<Object> add() {
		return sink -> {
			long handle = nextHandle.incrementAndGet();
			calls.add("add " + handle);
			return handle;
		};
	}



	/** A native add that waits, once it has been entered, until the test lets it return. */
	private final class BlockedAdd implements ToLongFunction<Object> {

		private final CountDownLatch entered = new CountDownLatch(1);

		private final CountDownLatch proceed = new CountDownLatch(1);


		@Override
		public long applyAsLong(Object sink) {
			entered.countDown();

			try {
				assertTrue(proceed.await(5, TimeUnit.SECONDS));
			}
			catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}

			return add().applyAsLong(sink);
		}

		void awaitEntered() throws InterruptedException {
			assertTrue(entered.await(5, TimeUnit.SECONDS));
		}

		void proceed() {
			proceed.countDown();
		}
	}
}
