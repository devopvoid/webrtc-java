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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Two linked signaling ends that hold every message until both peers have sent
 * an offer. Releasing them then makes the offers collide, whatever the timing.
 */
final class HeldSignaling {

	private final End first = new End();

	private final End second = new End();

	private boolean released;


	HeldSignaling() {
		first.remote = second;
		second.remote = first;
	}

	Signaling first() {
		return first;
	}

	Signaling second() {
		return second;
	}

	/**
	 * Waits until both peers have sent an offer, then delivers what they sent and
	 * lets later messages through.
	 */
	void releaseWhenBothOffered(long timeoutSeconds) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

		synchronized (this) {
			while (!(first.offered && second.offered)) {
				long left = deadline - System.nanoTime();

				if (left <= 0) {
					throw new IllegalStateException("Both peers did not send an offer");
				}

				TimeUnit.NANOSECONDS.timedWait(this, left);
			}

			released = true;

			first.flush();
			second.flush();
		}
	}



	private final class End implements Signaling {

		private final List<SignalMessage> held = new ArrayList<>();

		private End remote;

		private Consumer<SignalMessage> inbound;

		private boolean offered;


		@Override
		public void send(SignalMessage message) {
			synchronized (HeldSignaling.this) {
				if (message.type() == SignalMessage.Type.OFFER) {
					offered = true;
					HeldSignaling.this.notifyAll();
				}

				if (released) {
					remote.inbound.accept(message);
				}
				else {
					held.add(message);
				}
			}
		}

		@Override
		public void attach(Consumer<SignalMessage> inbound) {
			synchronized (HeldSignaling.this) {
				this.inbound = inbound;
			}
		}

		/** Delivers what this end sent to the other end's peer. */
		private void flush() {
			for (SignalMessage message : held) {
				remote.inbound.accept(message);
			}

			held.clear();
		}
	}
}
