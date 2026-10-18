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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.onvoid.webrtc.RTCPeerConnection;

/**
 * A connection to one remote peer, made by {@link WebRtc#createPeer}.
 * <p>
 * Every method may be called from any thread, including from a handler of
 * this peer, and none of them blocks. Methods that change the connection
 * return at once and take effect in the order they were called.
 * <p>
 * The handlers and the callbacks of the futures of one peer run on its event
 * thread, one at a time and in the order of the events. They may block, which
 * delays only the events of this peer, and they may call any method, including
 * {@link #close()}. They must not wait for a future of this API: chain the
 * next step instead.
 */
public interface Peer extends AutoCloseable {

	/**
	 * @return the name given in {@link PeerOptions.Builder#name(String)}, or
	 * one made up.
	 */
	String name();

	/**
	 * @return the last state the connection reported.
	 */
	PeerState state();

	/**
	 * Hands over a message the remote peer sent. Messages must be handed over
	 * in the order they were sent. A message that cannot be applied is
	 * reported to {@link #onError}.
	 *
	 * @param message the message the remote peer sent.
	 */
	void signal(SignalMessage message);

	/**
	 * Sends a track to the remote peer. With {@link Negotiation#AUTOMATIC} the
	 * peer renegotiates by itself. The same track may be sent on many peers.
	 *
	 * @param track     a track of the same runtime.
	 * @param streamIds the streams the remote peer sees the track in. Tracks
	 *                  added without any share one stream, so that a browser
	 *                  plays their audio and video together.
	 *
	 * @return the handle of the new sender, at once.
	 */
	Sender addTrack(LocalTrack track, String... streamIds);

	/**
	 * @return the senders that were added and not removed.
	 */
	List<Sender> senders();

	/**
	 * Calls the handler for every track the remote peer sends. Tracks that
	 * already arrived are handed to the new handler first.
	 *
	 * @param handler called on the event thread.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onTrack(Consumer<RemoteTrack> handler);

	/**
	 * @return the tracks the remote peer sends now.
	 */
	List<RemoteTrack> remoteTracks();

	/**
	 * Creates a reliable, ordered data channel. With {@link
	 * Negotiation#AUTOMATIC} the first channel makes the peer renegotiate.
	 *
	 * @param label the label the remote peer sees.
	 *
	 * @return the channel, at once. Messages sent before it opens are held.
	 */
	DataChannel createChannel(String label);

	/**
	 * Creates a data channel.
	 *
	 * @param label   the label the remote peer sees.
	 * @param options how the channel delivers.
	 *
	 * @return the channel, at once. Messages sent before it opens are held.
	 */
	DataChannel createChannel(String label, ChannelOptions options);

	/**
	 * Calls the handler for every channel the remote peer opens. Channels that
	 * already arrived are handed to the new handler first.
	 *
	 * @param handler called on the event thread.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onChannel(Consumer<DataChannel> handler);

	/**
	 * Calls the handler with the current state, and then with every change.
	 *
	 * @param handler called on the event thread.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onStateChange(Consumer<PeerState> handler);

	/**
	 * Calls the handler with failures nobody else hears of, such as a remote
	 * description that was rejected during automatic negotiation. Without a
	 * handler they go to the runtime's error handler.
	 *
	 * @param handler called on the event thread.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onError(Consumer<WebRtcException> handler);

	/**
	 * @return a future that completes when the peer is connected, or fails if
	 * the connection fails or closes first.
	 */
	CompletableFuture<Peer> whenConnected();

	/**
	 * @return a future that completes when the peer is closed.
	 */
	CompletableFuture<Void> whenClosed();

	/**
	 * @return the offer and answer exchange by hand.
	 *
	 * @throws IllegalStateException if the peer negotiates automatically.
	 */
	Negotiator negotiator();

	/**
	 * @return a future with the current statistics of the connection.
	 */
	CompletableFuture<PeerStats> stats();

	/**
	 * Calls the handler with the statistics of the connection, at a fixed
	 * interval. Bitrates are filled from the second call on.
	 *
	 * @param interval the time between two calls.
	 * @param handler  called on the event thread.
	 *
	 * @return the subscription that stops the calls.
	 */
	Subscription onStats(Duration interval, Consumer<PeerStats> handler);

	/**
	 * Gathers new ICE candidates and renegotiates, to recover a failed
	 * connection.
	 */
	void restartIce();

	/**
	 * Runs a function with the underlying connection, for what this API does
	 * not cover. It runs after the operations called before it, and before
	 * those called after it. Objects the function obtains belong to it, as in
	 * the low-level API, and must not be used after it returns.
	 *
	 * @param action the function, which must not block.
	 * @param <T>    the result type.
	 *
	 * @return a future with what the function returned.
	 */
	<T> CompletableFuture<T> withNative(Function<RTCPeerConnection, T> action);

	/**
	 * Closes the peer.
	 *
	 * @return a future that completes when the peer is closed.
	 */
	CompletableFuture<Void> closeAsync();

	/**
	 * Closes the peer, its channels and its senders, and ends its remote
	 * tracks. Local tracks stay open. Never blocks, may be called more than
	 * once and from a handler. Afterwards the only event is the {@link
	 * PeerState#CLOSED} state, and pending operations fail with a {@link
	 * PeerClosedException}.
	 */
	@Override
	void close();

}
