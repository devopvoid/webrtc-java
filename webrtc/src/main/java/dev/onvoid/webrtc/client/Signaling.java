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

import java.util.function.Consumer;

/**
 * Carries a peer's {@link SignalMessage}s to the remote peer. The library has
 * no transport of its own: a WebSocket, an HTTP endpoint, or anything else that
 * delivers messages in order will do.
 * <p>
 * In its simplest form a signaling is a lambda that sends, with incoming
 * messages handed to {@link Peer#signal(SignalMessage)}:
 * <pre>{@code
 * PeerOptions.builder().signaling(message -> socket.send(message.toJson()));
 * socket.onText(text -> peer.signal(SignalMessage.fromJson(text)));
 * }</pre>
 */
@FunctionalInterface
public interface Signaling {

	/**
	 * Sends a message to the remote peer. Called on the peer's event thread, in
	 * the order the remote peer must receive the messages. Blocking here only
	 * delays the events of this peer.
	 *
	 * @param message the message to send.
	 */
	void send(SignalMessage message);

	/**
	 * Called once, when the peer is created. A transport that receives
	 * messages may push them into {@code inbound} instead of calling {@link
	 * Peer#signal(SignalMessage)}. Does nothing by default.
	 * <p>
	 * The peer closes the returned subscription when it closes, after its last
	 * call of {@link #send(SignalMessage)}, on its event thread. Closing it
	 * removes this {@code inbound} and nothing else: the transport stays open,
	 * so it may outlive the peer and take the next one, or serve several peers
	 * at once. A transport that keeps {@code inbound} must let go of it there,
	 * or it keeps the closed peer, and the runtime with it, reachable.
	 *
	 * @param inbound takes the messages the remote peer sent, from any thread.
	 *
	 * @return removes {@code inbound} from the transport.
	 */
	default Subscription attach(Consumer<SignalMessage> inbound) {
		return () -> { };
	}

	/**
	 * Creates two linked ends: what one end sends, the peer attached to the
	 * other end receives. Suits tests and peers within one process.
	 *
	 * @return the two ends.
	 */
	static SignalingPair loopback() {
		return new SignalingPair();
	}

}
