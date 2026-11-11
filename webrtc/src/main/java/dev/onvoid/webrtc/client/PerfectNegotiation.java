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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;

/**
 * The automatic negotiation of a peer, perfect negotiation as the standard
 * describes it: the peer offers whenever WebRTC asks for a negotiation, and
 * answers the offers that come through its {@link Signaling}. When both
 * peers offer at once, the polite one gives way and the impolite one ignores
 * the other's offer.
 * <p>
 * Every exchange runs as an operation of the peer's {@link OperationChain},
 * and everything here is confined to the control executor.
 */
final class PerfectNegotiation {

	/** How long an impolite peer waits for an answer after it ignored an offer. */
	static final long COLLISION_TIMEOUT_MS = 5000;

	private final PeerImpl peer;

	private final PeerOptions options;

	private final OperationChain chain;

	private final NegotiationSteps steps;

	private boolean negotiationQueued;

	private boolean ignoreOffer;

	private int remoteAnswers;

	private ScheduledFuture<?> collisionCheck;


	PerfectNegotiation(PeerImpl peer, PeerOptions options, OperationChain chain, NegotiationSteps steps) {
		this.peer = peer;
		this.options = options;
		this.chain = chain;
		this.steps = steps;
	}

	/**
	 * @return whether the last remote offer was ignored, as an impolite peer
	 * does with one that collides with its own.
	 */
	boolean ignoresOffer() {
		return ignoreOffer;
	}

	/**
	 * Offers, once the exchange under way is done, when WebRTC asks for a
	 * negotiation. Several requests before the offer starts make one offer.
	 */
	void negotiationNeeded() {
		if (peer.isClosed() || options.negotiation != Negotiation.AUTOMATIC || negotiationQueued) {
			return;
		}

		negotiationQueued = true;

		chain.enqueue(() -> {
			negotiationQueued = false;

			if (peer.connection().getSignalingState() != RTCSignalingState.STABLE) {
				// WebRTC asks again once the exchange under way is done.
				return CompletableFuture.completedFuture(null);
			}

			return steps.setLocal().thenRun(peer::sendLocalDescription);
		}).whenComplete(this::reportFailure);
	}

	/**
	 * Applies what the remote peer signaled.
	 */
	void signal(SignalMessage message) {
		if (peer.isClosed()) {
			return;
		}

		switch (message.type()) {
			case OFFER:
			case ANSWER:
				if (options.negotiation == Negotiation.MANUAL) {
					peer.deliverError(new NegotiationException("The peer " + peer.name + " negotiates by hand. "
							+ "Apply descriptions through Peer.negotiator(), not Peer.signal()"));
					return;
				}

				chain.enqueue(() -> applyRemoteDescription(message.description()))
						.whenComplete(this::reportFailure);
				break;

			case CANDIDATE:
				chain.enqueue(() -> ignoreOffer
						? CompletableFuture.<Void>completedFuture(null)
						: steps.addRemoteCandidate(message.candidate()))
						.whenComplete(this::reportFailure);
				break;

			default:
				// WebRTC needs no end of candidates.
				break;
		}
	}

	/**
	 * Stops watching for a collision neither peer gives way in, once the peer
	 * is closing.
	 */
	void close() {
		if (collisionCheck != null) {
			collisionCheck.cancel(false);
			collisionCheck = null;
		}
	}

	private CompletableFuture<Void> applyRemoteDescription(RTCSessionDescription description) {
		boolean offer = description.sdpType == RTCSdpType.OFFER;
		boolean collision = offer && peer.connection().getSignalingState() != RTCSignalingState.STABLE;

		ignoreOffer = !options.polite && collision;

		if (ignoreOffer) {
			// The polite peer gives way and answers this peer's offer instead.
			watchCollision();
			return CompletableFuture.completedFuture(null);
		}

		// A polite peer's own pending offer is rolled back implicitly.
		return steps.setRemote(description).thenCompose(ignored -> {
			steps.releaseHeldCandidates();

			if (!offer) {
				remoteAnswers++;
				return CompletableFuture.completedFuture(null);
			}

			return steps.setLocal().thenRun(peer::sendLocalDescription);
		});
	}

	/**
	 * Tells the application when the remote peer never answered the offer this
	 * peer kept, which happens when both peers are impolite.
	 */
	private void watchCollision() {
		if (collisionCheck != null) {
			return;
		}

		int answers = remoteAnswers;

		try {
			collisionCheck = peer.rtc.timer.schedule(() -> peer.control.execute(() -> {
				collisionCheck = null;

				if (!peer.isClosed() && remoteAnswers == answers
						&& peer.connection().getSignalingState() == RTCSignalingState.HAVE_LOCAL_OFFER) {
					peer.deliverError(new NegotiationException("Both peers sent an offer at once, and both are "
							+ "impolite, so neither gives way. Build exactly one of them with "
							+ "PeerOptions.Builder.polite(true)"));
				}
			}), COLLISION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException e) {
			// The runtime is closing, and the peer with it.
		}
	}

	private void reportFailure(Object value, Throwable error) {
		if (error != null && !(PeerImpl.unwrap(error) instanceof PeerClosedException)) {
			peer.deliverError(PeerImpl.asWebRtcException(error, "Negotiation of " + peer.name + " failed"));
		}
	}
}
