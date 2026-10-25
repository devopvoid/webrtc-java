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

import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelSendObserver;

/**
 * The {@link DataChannel} of a {@link PeerImpl}.
 * <p>
 * The native channel is used on the peer's control executor, where sends wait
 * while the channel is not open or holds more than {@link #HIGH_WATER_MARK}
 * bytes. What waits is bounded by the peer's channel send limit, beyond which
 * sends fail at once. Handlers run on the peer's event executor.
 * <p>
 * Received messages are counted as they arrive, before they are queued for
 * the handlers, and until the handlers are done with them. When they reach
 * the peer's channel receive limit, the channel closes: WebRTC cannot be told
 * to stop receiving, and dropping messages of a reliable channel would lose
 * them unnoticed.
 */
final class DataChannelImpl implements DataChannel {

	/** Bytes the native channel may hold before sends wait in Java. */
	static final long HIGH_WATER_MARK = 1024 * 1024;

	private final PeerImpl peer;

	private final String label;

	/** The most bytes {@link #held} may reach. */
	private final long sendLimit;

	/** Bytes sent and not yet handed to the transport. */
	private final AtomicLong held = new AtomicLong();

	/** The most bytes {@link #received} may reach. */
	private final long receiveLimit;

	/** Bytes received that the handlers are not done with, or that wait for one. */
	private final AtomicLong received = new AtomicLong();

	/** Set once received messages went over the limit, after which none is queued. */
	private final AtomicBoolean overflowed = new AtomicBoolean();

	private volatile ChannelState state = ChannelState.CONNECTING;

	/** What the native channel holds, as last read on the control executor. */
	private volatile long nativeBuffered;

	// Confined to the peer's control executor.

	private RTCDataChannel channel;

	private final ArrayDeque<Pending> pending = new ArrayDeque<>();

	private long inFlight;

	private boolean released;

	// Confined to the peer's event executor.

	private ChannelState deliveredState = ChannelState.CONNECTING;

	private final ArrayDeque<Message> early = new ArrayDeque<>();

	private long earlyBytes;

	private final Handlers<Message> messageHandlers;

	private final Handlers<ChannelState> stateHandlers;

	private final ClientFuture<DataChannel> opened = new ClientFuture<>();


	/**
	 * A channel this side creates. It is bound once the control executor
	 * created the native channel.
	 */
	DataChannelImpl(PeerImpl peer, String label) {
		this.peer = peer;
		this.label = label;
		this.sendLimit = peer.channelSendLimit();
		this.receiveLimit = peer.channelReceiveLimit();
		this.messageHandlers = new Handlers<>(peer.rtc::reportError);
		this.stateHandlers = new Handlers<>(peer.rtc::reportError);

		peer.rtc.track(opened, peer.events);
	}

	/**
	 * A channel the remote peer opened, called on WebRTC's signaling thread.
	 * It is observed at once, so that no message is missed.
	 */
	DataChannelImpl(PeerImpl peer, RTCDataChannel remote) {
		this(peer, remote.getLabel());

		this.channel = remote;
		remote.registerObserver(new Observer(remote));

		ChannelState current = ChannelState.of(remote.getState());

		if (current != ChannelState.CONNECTING) {
			peer.control.execute(() -> stateChanged(current));
		}
	}

	/** Binds the native channel this side created, on the control executor. */
	void bind(RTCDataChannel created) {
		channel = created;
		created.registerObserver(new Observer(created));

		ChannelState current = ChannelState.of(created.getState());

		if (current != ChannelState.CONNECTING) {
			stateChanged(current);
		}
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
		return ClientFuture.copyOf(opened);
	}

	@Override
	public CompletableFuture<Void> send(String text) {
		requireNonNull(text, "text");

		return enqueue(text.getBytes(StandardCharsets.UTF_8), false);
	}

	@Override
	public CompletableFuture<Void> send(byte[] data) {
		requireNonNull(data, "data");

		return enqueue(data.clone(), true);
	}

	@Override
	public CompletableFuture<Void> send(ByteBuffer data) {
		requireNonNull(data, "data");

		byte[] copy = new byte[data.remaining()];
		data.duplicate().get(copy);

		return enqueue(copy, true);
	}

	@Override
	public long bufferedAmount() {
		return held.get() + nativeBuffered;
	}

	@Override
	public Subscription onMessage(Consumer<Message> handler) {
		requireNonNull(handler, "handler");

		return peer.subscribe(() -> {
			Subscription subscription = messageHandlers.add(handler);

			if (!early.isEmpty()) {
				List<Message> kept = new ArrayList<>(early);
				long keptBytes = earlyBytes;
				early.clear();
				earlyBytes = 0;

				for (Message message : kept) {
					peer.call(handler, message);
				}

				received.addAndGet(-keptBytes);
			}

			return subscription;
		});
	}

	@Override
	public Subscription onStateChange(Consumer<ChannelState> handler) {
		requireNonNull(handler, "handler");

		return peer.subscribe(() -> {
			peer.call(handler, deliveredState);
			return stateHandlers.add(handler);
		});
	}

	@Override
	public void close() {
		peer.control.execute(() -> {
			if (released) {
				return;
			}
			if (channel == null) {
				closeFromPeer(new PeerClosedException("The data channel " + label + " is closed"));
				return;
			}

			try {
				channel.close();
			}
			catch (Throwable e) {
				closeFromPeer(new PeerClosedException("The data channel " + label + " is closed"));
			}
		});
	}

	@Override
	public String toString() {
		return "DataChannel[" + label + ", " + state + "]";
	}

	/**
	 * Fails what waits, lets go of the native channel and reports the channel
	 * closed. On the control executor.
	 */
	void closeFromPeer(WebRtcException error) {
		if (released) {
			return;
		}

		released = true;
		failPending(error);

		if (channel != null) {
			try {
				channel.unregisterObserver();
				channel.close();
				channel.dispose();
			}
			catch (Throwable e) {
				peer.rtc.reportError(e);
			}

			channel = null;
		}

		nativeBuffered = 0;
		state = ChannelState.CLOSED;
		peer.channelClosed(this);

		peer.events.execute(() -> deliverState(ChannelState.CLOSED, error),
				() -> settleOpened(ChannelState.CLOSED, error));
	}

	private CompletableFuture<Void> enqueue(byte[] data, boolean binary) {
		long size = data.length;
		long before = held.getAndUpdate(amount -> amount + size <= sendLimit ? amount + size : amount);

		if (before + size > sendLimit) {
			return peer.toPublic(ClientFuture.failed(new ChannelFullException("The data channel " + label
					+ " holds " + before + " bytes waiting to be sent, and " + size + " more would exceed "
					+ "its limit of " + sendLimit)));
		}

		CompletableFuture<Void> sent = new CompletableFuture<>();

		peer.control.execute(() -> {
			if (released || state == ChannelState.CLOSING || state == ChannelState.CLOSED) {
				held.addAndGet(-data.length);
				sent.completeExceptionally(new PeerClosedException("The data channel " + label + " is closed"));
				return;
			}

			pending.add(new Pending(data, binary, sent));
			pump();
		});

		return peer.toPublic(sent);
	}

	/** Hands waiting messages to the transport while it has room. */
	private void pump() {
		if (released || channel == null || state != ChannelState.OPEN) {
			return;
		}

		nativeBuffered = channel.getBufferedAmount();

		while (!pending.isEmpty() && nativeBuffered + inFlight < HIGH_WATER_MARK) {
			Pending message = pending.poll();
			int size = message.data.length;

			inFlight += size;

			channel.sendAsync(new RTCDataChannelBuffer(ByteBuffer.wrap(message.data), message.binary),
					new RTCDataChannelSendObserver() {

						@Override
						public void onSuccess() {
							peer.control.execute(() -> sendDone(message, size, null));
						}

						@Override
						public void onFailure(String error) {
							peer.control.execute(() -> sendDone(message, size, error));
						}
					});
		}
	}

	private void sendDone(Pending message, int size, String error) {
		inFlight -= size;
		held.addAndGet(-size);

		if (error == null) {
			message.sent.complete(null);
		}
		else if (released || state != ChannelState.OPEN) {
			message.sent.completeExceptionally(new PeerClosedException("The data channel " + label + " closed: " + error));
		}
		else {
			message.sent.completeExceptionally(new WebRtcException("Sending on the data channel " + label + " failed: " + error));
		}

		pump();
	}

	private void failPending(WebRtcException error) {
		for (Pending message : pending) {
			held.addAndGet(-message.data.length);
			message.sent.completeExceptionally(error);
		}

		pending.clear();
	}

	private void stateChanged(ChannelState newState) {
		if (released) {
			return;
		}

		if (newState == ChannelState.CLOSED) {
			closeFromPeer(new PeerClosedException("The data channel " + label + " is closed"));
			return;
		}

		state = newState;

		if (newState == ChannelState.OPEN) {
			pump();
		}
		else if (newState == ChannelState.CLOSING) {
			failPending(new PeerClosedException("The data channel " + label + " is closing"));
		}

		peer.events.execute(() -> deliverState(newState, null), () -> settleOpened(newState, null));
	}

	private void bufferChanged() {
		if (!released && channel != null) {
			pump();
		}
	}

	/** Closes a channel whose received messages went over the limit. */
	private void receiveLimitExceeded() {
		if (released) {
			return;
		}

		WebRtcException error = new WebRtcException("The data channel " + label + " closed: its message "
				+ "handlers fell more than " + receiveLimit + " bytes of received messages behind");

		peer.deliverError(error);
		closeFromPeer(error);
	}

	// On the event executor.

	/**
	 * Completes the opened future for the state, which is all that is left of
	 * delivering it when the event executor rejects that.
	 */
	private void settleOpened(ChannelState newState, WebRtcException error) {
		if (newState == ChannelState.OPEN) {
			opened.complete(this);
		}
		else if (newState == ChannelState.CLOSED) {
			opened.completeExceptionally(error != null ? error
					: new PeerClosedException("The data channel " + label + " is closed"));
		}
	}

	private void deliverState(ChannelState newState, WebRtcException error) {
		if (newState == deliveredState || deliveredState == ChannelState.CLOSED) {
			return;
		}

		deliveredState = newState;
		stateHandlers.accept(newState);
		settleOpened(newState, error);

		if (newState == ChannelState.CLOSED) {
			messageHandlers.clear();
			stateHandlers.clear();
			early.clear();
			received.addAndGet(-earlyBytes);
			earlyBytes = 0;
		}
	}

	private void deliverMessage(Message message) {
		if (deliveredState == ChannelState.CLOSED) {
			received.addAndGet(-message.size());
			return;
		}

		if (messageHandlers.isEmpty()) {
			// Kept for the next handler, and counted until it is done with them.
			early.add(message);
			earlyBytes += message.size();
		}
		else {
			try {
				messageHandlers.accept(message);
			}
			finally {
				received.addAndGet(-message.size());
			}
		}
	}



	/**
	 * Hears what the native channel reports, on a WebRTC thread, and only
	 * copies and posts it.
	 */
	private final class Observer implements RTCDataChannelObserver {

		private final RTCDataChannel observed;


		Observer(RTCDataChannel observed) {
			this.observed = observed;
		}

		@Override
		public void onBufferedAmountChange(long sentDataSize) {
			peer.control.execute(DataChannelImpl.this::bufferChanged);
		}

		@Override
		public void onStateChange() {
			// The state is read here, while the native channel surely exists.
			ChannelState newState = ChannelState.of(observed.getState());

			peer.control.execute(() -> stateChanged(newState));
		}

		@Override
		public void onMessage(RTCDataChannelBuffer buffer) {
			// The buffer is valid only during this call.
			if (overflowed.get()) {
				// The channel is closing; nothing after the message over the limit is delivered.
				return;
			}

			int size = buffer.data.remaining();

			// Counted before it is queued, since that queue is what grows while
			// the handlers fall behind.
			if (received.addAndGet(size) > receiveLimit) {
				received.addAndGet(-size);

				if (overflowed.compareAndSet(false, true)) {
					peer.control.execute(DataChannelImpl.this::receiveLimitExceeded);
				}
				return;
			}

			byte[] data = new byte[size];
			buffer.data.duplicate().get(data);

			Message message = new Message(data, buffer.binary);

			peer.events.execute(() -> deliverMessage(message), () -> received.addAndGet(-size));
		}
	}



	private static final class Pending {

		final byte[] data;

		final boolean binary;

		final CompletableFuture<Void> sent;


		Pending(byte[] data, boolean binary, CompletableFuture<Void> sent) {
			this.data = data;
			this.binary = binary;
			this.sent = sent;
		}
	}
}
