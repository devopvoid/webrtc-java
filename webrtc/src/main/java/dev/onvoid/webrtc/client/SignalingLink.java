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

import java.util.concurrent.atomic.AtomicReference;

/**
 * The link of a peer to its {@link Signaling}, if it has one.
 * <p>
 * The transport is application code, so messages go out on the event executor.
 * The subscription is the only state, and atomic, since the thread that
 * attaches and the event executor that lets go may race.
 */
final class SignalingLink {

	private static final Subscription DETACHED = () -> { };

	private final PeerImpl peer;

	private final Signaling signaling;

	/**
	 * What {@link Signaling#attach} returned, or null before, and {@link
	 * #DETACHED} once the peer has let go of the signaling on close.
	 */
	private final AtomicReference<Subscription> subscription = new AtomicReference<>();


	/**
	 * @param peer      the peer.
	 * @param signaling the transport, or null for none.
	 */
	SignalingLink(PeerImpl peer, Signaling signaling) {
		this.peer = peer;
		this.signaling = signaling;
	}

	/**
	 * Attaches the peer to the transport, unless it began to close.
	 */
	void attach() {
		if (signaling == null || peer.isClosing()) {
			return;
		}

		Subscription attached = signaling.attach(peer::signal);

		if (attached != null && !subscription.compareAndSet(null, attached)) {
			// Closed while attaching: the close found nothing to let go of
			// yet, so this lets go of what attach returned.
			close(attached);
		}
	}

	/**
	 * Sends a message from the event executor, behind what is queued there.
	 */
	void send(SignalMessage message) {
		if (signaling == null) {
			return;
		}

		peer.events.execute(() -> {
			if (subscription.get() == DETACHED) {
				// The transport may take another peer by now.
				return;
			}

			try {
				signaling.send(message);
			}
			catch (Throwable e) {
				peer.deliverError(new WebRtcException("The signaling failed to send " + message.type(), e));
			}
		});
	}

	/**
	 * Lets go of the signaling, once: a transport that outlives the peer must
	 * not keep it, and the runtime with it, reachable.
	 */
	void detach() {
		Subscription attached = subscription.getAndSet(DETACHED);

		if (attached != null && attached != DETACHED) {
			close(attached);
		}
	}

	private void close(Subscription attached) {
		try {
			attached.close();
		}
		catch (Throwable e) {
			peer.rtc.reportError(new WebRtcException("Detaching " + peer.name + " from its signaling failed", e));
		}
	}
}
