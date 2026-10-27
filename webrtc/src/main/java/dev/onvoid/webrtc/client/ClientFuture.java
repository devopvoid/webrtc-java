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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The futures this API hands out. They complete on the event thread of their
 * peer, so waiting for one there would never return: {@link #join()} and
 * {@link #get()} refuse with a {@link BlockingCallException} instead of
 * deadlocking. A future that is already done may be read anywhere.
 * <p>
 * The futures derived from this one, with {@code thenApply} and the like, are
 * of this class too and refuse the same way.
 *
 * @param <T> the result type.
 */
final class ClientFuture<T> extends CompletableFuture<T> {

	static <T> ClientFuture<T> failed(Throwable error) {
		ClientFuture<T> future = new ClientFuture<>();
		future.completeExceptionally(error);
		return future;
	}

	static <T> ClientFuture<T> completed(T value) {
		ClientFuture<T> future = new ClientFuture<>();
		future.complete(value);
		return future;
	}

	/**
	 * Hands out a future of its own for a future shared by many callers: a
	 * caller that cancels or completes its copy changes nothing for the
	 * others, nor for what waits for the shared one.
	 *
	 * @param shared the future the copy completes with, on the same thread.
	 *
	 * @return a new future.
	 */
	static <T> ClientFuture<T> copyOf(CompletableFuture<T> shared) {
		ClientFuture<T> copy = new ClientFuture<>();
		shared.whenComplete((value, error) -> PeerImpl.complete(copy, value, error));
		return copy;
	}

	@Override
	public T get() throws InterruptedException, ExecutionException {
		checkBlocking();
		return super.get();
	}

	@Override
	public T get(long timeout, TimeUnit unit)
			throws InterruptedException, ExecutionException, TimeoutException {
		checkBlocking();
		return super.get(timeout, unit);
	}

	@Override
	public T join() {
		checkBlocking();
		return super.join();
	}

	/**
	 * Makes the futures derived from this one refuse to be waited on from an
	 * event thread as well.
	 *
	 * @param <U> the result type of the derived future.
	 *
	 * @return a new incomplete future.
	 */
	@Override
	public <U> CompletableFuture<U> newIncompleteFuture() {
		return new ClientFuture<>();
	}

	private void checkBlocking() {
		if (!isDone() && SerialExecutor.onEventThread()) {
			throw new BlockingCallException("A future was waited on from the event thread "
					+ Thread.currentThread().getName() + ", which is the thread that completes it. "
					+ "Chain the next step with thenAccept or thenCompose instead of calling join() or get().");
		}
	}
}
