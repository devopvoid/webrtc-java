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
 * bytes. Handlers run on the peer's event executor.
 */
final class DataChannelImpl implements DataChannel {

	/** Bytes the native channel may hold before sends wait in Java. */
	static final long HIGH_WATER_MARK = 1024 * 1024;

	/** Bytes of received messages kept while there is no message handler. */
	static final long EARLY_LIMIT = 16 * 1024 * 1024;

	private final PeerImpl peer;

	private final String label;

	/** Bytes sent and not yet handed to the transport. */
	private final AtomicLong held = new AtomicLong();

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

	private boolean earlyDropReported;

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
		this.messageHandlers = new Handlers<>(peer.rtc::reportError);
		this.stateHandlers = new Handlers<>(peer.rtc::reportError);
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
		return opened;
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
				early.clear();
				earlyBytes = 0;

				for (Message message : kept) {
					peer.call(handler, message);
				}
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

		peer.events.execute(() -> deliverState(ChannelState.CLOSED, error));
	}

	private CompletableFuture<Void> enqueue(byte[] data, boolean binary) {
		CompletableFuture<Void> sent = new CompletableFuture<>();
		held.addAndGet(data.length);

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

		peer.events.execute(() -> deliverState(newState, null));
	}

	private void bufferChanged() {
		if (!released && channel != null) {
			pump();
		}
	}

	// On the event executor.

	private void deliverState(ChannelState newState, WebRtcException error) {
		if (newState == deliveredState || deliveredState == ChannelState.CLOSED) {
			return;
		}

		deliveredState = newState;
		stateHandlers.accept(newState);

		if (newState == ChannelState.OPEN) {
			opened.complete(this);
		}
		else if (newState == ChannelState.CLOSED) {
			opened.completeExceptionally(error != null ? error
					: new PeerClosedException("The data channel " + label + " is closed"));

			messageHandlers.clear();
			stateHandlers.clear();
			early.clear();
			earlyBytes = 0;
		}
	}

	private void deliverMessage(Message message) {
		if (deliveredState == ChannelState.CLOSED) {
			return;
		}

		if (messageHandlers.isEmpty()) {
			early.add(message);
			earlyBytes += message.size();

			// Kept for a handler that may never come, so their memory is bounded.
			while (earlyBytes > EARLY_LIMIT && early.size() > 1) {
				earlyBytes -= early.poll().size();

				if (!earlyDropReported) {
					earlyDropReported = true;
					peer.deliverError(new WebRtcException("The data channel " + label + " dropped received messages: "
							+ "more than " + (EARLY_LIMIT >> 20) + " MiB arrived while it had no message handler"));
				}
			}
		}
		else {
			messageHandlers.accept(message);
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
			byte[] data = new byte[buffer.data.remaining()];
			buffer.data.duplicate().get(data);

			Message message = new Message(data, buffer.binary);

			peer.events.execute(() -> deliverMessage(message));
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
