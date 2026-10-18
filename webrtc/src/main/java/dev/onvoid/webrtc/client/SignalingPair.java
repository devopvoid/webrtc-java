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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Two linked {@link Signaling} ends, made by {@link Signaling#loopback()}.
 * Messages sent through one end reach the peer attached to the other end, in
 * order. Messages sent before that peer exists are held until it attaches.
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
		public synchronized void attach(Consumer<SignalMessage> inbound) {
			if (this.inbound != null) {
				throw new IllegalStateException("This end of the loopback is already attached to a peer");
			}

			this.inbound = inbound;

			// Under the lock, so that nothing delivered meanwhile overtakes them.
			// A peer's inbound only queues the message.
			held.forEach(inbound);
			held.clear();
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
