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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A data channel of a {@link Peer}, created with {@link Peer#createChannel} or
 * opened by the remote peer.
 * <p>
 * Handlers run on the event thread of the peer, in order with its other
 * events.
 */
public interface DataChannel extends AutoCloseable {

	/**
	 * @return the label.
	 */
	String label();

	/**
	 * @return the current state.
	 */
	ChannelState state();

	/**
	 * @return a future that completes when the channel is open, or fails if it
	 * closes first.
	 */
	CompletableFuture<DataChannel> whenOpen();

	/**
	 * Sends a text message. Messages sent before the channel opens are held
	 * until it does.
	 *
	 * @param text the message.
	 *
	 * @return a future that completes when the message is handed to the
	 * transport. While a lot of data waits to be sent, the future completes
	 * only once the backlog shrank, so a loop that waits for each send slows
	 * down by itself. The future fails at once with a {@link
	 * ChannelFullException} when the message would take the backlog beyond
	 * {@link PeerOptions.Builder#channelSendLimit(long)}.
	 */
	CompletableFuture<Void> send(String text);

	/**
	 * Sends a binary message. The array is copied before the call returns.
	 *
	 * @param data the message.
	 *
	 * @return a future that completes when the message is handed to the
	 * transport, or fails as {@link #send(String)} describes.
	 */
	CompletableFuture<Void> send(byte[] data);

	/**
	 * Sends the remaining bytes of a buffer as a binary message. They are
	 * copied before the call returns; the buffer's position does not change.
	 *
	 * @param data the message.
	 *
	 * @return a future that completes when the message is handed to the
	 * transport, or fails as {@link #send(String)} describes.
	 */
	CompletableFuture<Void> send(ByteBuffer data);

	/**
	 * @return the bytes sent and not yet passed to the network, including
	 * those held by this API.
	 */
	long bufferedAmount();

	/**
	 * Calls the handler for each message received. Messages that arrive while
	 * the channel has no handler are kept for the next one, up to 16 MiB;
	 * beyond that the oldest are dropped, which is reported to the peer's
	 * {@link Peer#onError} handlers.
	 *
	 * @param handler called on the peer's event thread.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onMessage(Consumer<Message> handler);

	/**
	 * Calls the handler with the current state, and then with every change.
	 *
	 * @param handler called on the peer's event thread.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onStateChange(Consumer<ChannelState> handler);

	/**
	 * Closes the channel. Messages not yet handed to the transport fail with a
	 * {@link PeerClosedException}. Never blocks, may be called more than once.
	 */
	@Override
	void close();

}
