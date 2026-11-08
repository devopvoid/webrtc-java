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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One end of an in-memory data channel, for tests of what is built on {@link
 * DataChannel} without native code. Each end has an event thread of its own,
 * where its handlers run and its send futures complete, and delivers in
 * order. Messages that arrive without a handler are kept for the next one.
 */
public final class LoopbackChannel implements DataChannel {

	private final String label;

	/** Shared by both ends, so that a send and a close of either never interleave. */
	private final Object lock;

	private final ExecutorService events;

	private LoopbackChannel remote;

	private volatile ChannelState state = ChannelState.OPEN;

	private volatile UnaryOperator<byte[]> outbound = UnaryOperator.identity();

	/** Binary bytes this end sent, before the interceptor. */
	public final AtomicLong sentBytes = new AtomicLong();

	// Confined to the event thread.

	private final List<Consumer<Message>> messageHandlers = new ArrayList<>();

	private final List<Consumer<ChannelState>> stateHandlers = new ArrayList<>();

	private final ArrayDeque<Message> early = new ArrayDeque<>();


	private LoopbackChannel(String label, String side, Object lock) {
		this.label = label;
		this.lock = lock;
		this.events = Executors.newSingleThreadExecutor(task -> {
			Thread thread = new Thread(task, "loopback-" + label + "-" + side);
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * @return two open ends of a channel.
	 */
	public static LoopbackChannel[] pair(String label) {
		Object lock = new Object();
		LoopbackChannel first = new LoopbackChannel(label, "first", lock);
		LoopbackChannel second = new LoopbackChannel(label, "second", lock);
		first.remote = second;
		second.remote = first;
		return new LoopbackChannel[] { first, second };
	}

	/**
	 * Passes each binary message this end sends through the interceptor, on
	 * the sending thread; a null result drops the message.
	 */
	public void intercept(UnaryOperator<byte[]> interceptor) {
		this.outbound = interceptor;
	}

	@Override
	public String label() {
		return label;
	}

	@Override
	public ChannelState state() {
		return state;
	}

	@Override
	public CompletableFuture<DataChannel> whenOpen() {
		return CompletableFuture.completedFuture(this);
	}

	@Override
	public CompletableFuture<Void> send(String text) {
		return deliver(text.getBytes(StandardCharsets.UTF_8), false);
	}

	@Override
	public CompletableFuture<Void> send(byte[] data) {
		return deliver(data.clone(), true);
	}

	@Override
	public CompletableFuture<Void> send(ByteBuffer data) {
		byte[] copy = new byte[data.remaining()];
		data.duplicate().get(copy);
		return deliver(copy, true);
	}

	@Override
	public long bufferedAmount() {
		return 0;
	}

	@Override
	public Subscription onMessage(Consumer<Message> handler) {
		run(() -> {
			messageHandlers.add(handler);

			while (!early.isEmpty()) {
				handler.accept(early.poll());
			}
		}, () -> {});

		return () -> run(() -> messageHandlers.remove(handler), () -> {});
	}

	@Override
	public Subscription onStateChange(Consumer<ChannelState> handler) {
		run(() -> {
			handler.accept(state);
			stateHandlers.add(handler);
		}, () -> handler.accept(ChannelState.CLOSED));

		return () -> run(() -> stateHandlers.remove(handler), () -> {});
	}

	/** Closes both ends. */
	@Override
	public void close() {
		synchronized (lock) {
			closeHere();
			remote.closeHere();
		}
	}

	private void run(Runnable task, Runnable closed) {
		synchronized (lock) {
			if (events.isShutdown()) {
				closed.run();
			}
			else {
				events.execute(task);
			}
		}
	}

	private void closeHere() {
		if (state == ChannelState.CLOSED) {
			return;
		}

		state = ChannelState.CLOSED;

		events.execute(() -> {
			for (Consumer<ChannelState> handler : new ArrayList<>(stateHandlers)) {
				handler.accept(ChannelState.CLOSED);
			}

			messageHandlers.clear();
			stateHandlers.clear();
			early.clear();
		});
		events.shutdown();
	}

	private CompletableFuture<Void> deliver(byte[] data, boolean binary) {
		CompletableFuture<Void> sent = new CompletableFuture<>();

		synchronized (lock) {
			if (state != ChannelState.OPEN) {
				sent.completeExceptionally(new PeerClosedException("The data channel " + label + " is closed"));
				return sent;
			}

			byte[] out = data;

			if (binary) {
				sentBytes.addAndGet(data.length);
				out = outbound.apply(data);
			}

			if (out != null && state == ChannelState.OPEN) {
				remote.receive(new Message(out, binary));
			}

			if (state == ChannelState.OPEN) {
				events.execute(() -> sent.complete(null));
			}
			else {
				sent.completeExceptionally(new PeerClosedException("The data channel " + label + " closed"));
			}
		}

		return sent;
	}

	private void receive(Message message) {
		if (state != ChannelState.OPEN) {
			return;
		}

		events.execute(() -> {
			if (messageHandlers.isEmpty()) {
				early.add(message);
				return;
			}

			for (Consumer<Message> handler : new ArrayList<>(messageHandlers)) {
				handler.accept(message);
			}
		});
	}
}
