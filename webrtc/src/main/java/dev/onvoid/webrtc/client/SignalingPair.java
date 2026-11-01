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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Two linked {@link Signaling} ends, made by {@link Signaling#loopback()}.
 * Messages sent through one end reach the peer attached to the other end, in
 * order. Messages sent while no peer is attached there, before the first one
 * or after it closed, are held until the next one attaches. An end takes one
 * peer at a time.
 */
public final class SignalingPair {

	private final End first = new End();

	private final End second = new End();


	SignalingPair() {
		first.remote = second;
		second.remote = first;
	}

	/**
	 * @return the end for the first peer.
	 */
	public Signaling first() {
		return first;
	}

	/**
	 * @return the end for the second peer.
	 */
	public Signaling second() {
		return second;
	}



	private static final class End implements Signaling {

		private final List<SignalMessage> held = new ArrayList<>();

		private End remote;

		private Consumer<SignalMessage> inbound;


		@Override
		public void send(SignalMessage message) {
			remote.deliver(message);
		}

		@Override
		public synchronized Subscription attach(Consumer<SignalMessage> inbound) {
			requireNonNull(inbound, "inbound");

			if (this.inbound != null) {
				throw new IllegalStateException("This end of the loopback is already attached to a peer");
			}

			this.inbound = inbound;

			// Under the lock, so that nothing delivered meanwhile overtakes them.
			// A peer's inbound only queues the message.
			held.forEach(inbound);
			held.clear();

			return () -> detach(inbound);
		}

		private synchronized void detach(Consumer<SignalMessage> inbound) {
			// Only the registration it was made for: closed again once the
			// next peer has attached, it leaves that one alone.
			if (this.inbound == inbound) {
				this.inbound = null;
			}
		}

		private synchronized void deliver(SignalMessage message) {
			if (inbound == null) {
				held.add(message);
			}
			else {
				inbound.accept(message);
			}
		}
	}
}
