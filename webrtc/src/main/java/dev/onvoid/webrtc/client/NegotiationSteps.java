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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dev.onvoid.webrtc.AddIceCandidateObserver;
import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.internal.Timeouts;

/**
 * The calls into the connection that negotiation is made of, each as a step
 * of the running operation of the {@link OperationChain}, so that its timeout
 * fails them. WebRTC answers them on its own threads; the answers are posted
 * to the control executor, where everything here is confined to.
 */
final class NegotiationSteps {

	private final SerialExecutor control;

	private final ScheduledExecutorService timer;

	private final OperationChain chain;

	private final Supplier<RTCPeerConnection> connection;

	private final Duration gatheringTimeout;

	private final BooleanSupplier ignoringOffer;

	/** The remote candidates that came before a remote description. */
	private final List<HeldCandidate> heldCandidates = new ArrayList<>();

	private final List<CompletableFuture<Void>> gatheringWaiters = new ArrayList<>();


	/**
	 * @param control          the control executor of the peer.
	 * @param timer            schedules the end of waiting for gathering.
	 * @param chain            the operations chain the steps belong to.
	 * @param connection       the connection, once there is one.
	 * @param gatheringTimeout how long to wait for gathering to complete.
	 * @param ignoringOffer    whether the peer ignored a remote offer, whose
	 *                         candidates then fail to be added, harmlessly.
	 */
	NegotiationSteps(SerialExecutor control, ScheduledExecutorService timer, OperationChain chain,
			Supplier<RTCPeerConnection> connection, Duration gatheringTimeout, BooleanSupplier ignoringOffer) {
		this.control = control;
		this.timer = timer;
		this.chain = chain;
		this.connection = connection;
		this.gatheringTimeout = gatheringTimeout;
		this.ignoringOffer = ignoringOffer;
	}

	/**
	 * Applies the description WebRTC finds for the signaling state, an offer
	 * or an answer.
	 */
	CompletableFuture<Void> setLocal() {
		CompletableFuture<Void> future = chain.step();
		connection.get().setLocalDescription(descriptionObserver(future, "Applying the local description failed"));
		return future;
	}

	CompletableFuture<Void> setLocal(RTCSessionDescription description) {
		CompletableFuture<Void> future = chain.step();
		connection.get().setLocalDescription(description,
				descriptionObserver(future, "Applying the local description failed"));
		return future;
	}

	CompletableFuture<Void> setRemote(RTCSessionDescription description) {
		CompletableFuture<Void> future = chain.step();
		connection.get().setRemoteDescription(description, descriptionObserver(future,
				"Applying the remote " + description.sdpType.name().toLowerCase() + " failed"));
		return future;
	}

	CompletableFuture<RTCSessionDescription> createOffer() {
		CompletableFuture<RTCSessionDescription> future = chain.step();
		connection.get().createOffer(new RTCOfferOptions(), createObserver(future, "Creating the offer failed"));
		return future;
	}

	CompletableFuture<RTCSessionDescription> createAnswer() {
		CompletableFuture<RTCSessionDescription> future = chain.step();
		connection.get().createAnswer(new RTCAnswerOptions(), createObserver(future, "Creating the answer failed"));
		return future;
	}

	/**
	 * Takes back what is pending of an offer/answer exchange, whichever side
	 * made the offer, so that signaling is stable again.
	 */
	CompletableFuture<Void> rollback() {
		RTCSignalingState state = connection.get().getSignalingState();

		if (state == RTCSignalingState.STABLE) {
			return CompletableFuture.completedFuture(null);
		}

		RTCSessionDescription description = new RTCSessionDescription(RTCSdpType.ROLLBACK, "");

		return state == RTCSignalingState.HAVE_REMOTE_OFFER || state == RTCSignalingState.HAVE_LOCAL_PR_ANSWER
				? setRemote(description)
				: setLocal(description);
	}

	/**
	 * Adds a remote candidate, or holds it while there is no remote
	 * description to add it to.
	 */
	CompletableFuture<Void> addRemoteCandidate(RTCIceCandidate candidate) {
		CompletableFuture<Void> future = chain.step();

		if (connection.get().getRemoteDescription() == null) {
			heldCandidates.add(new HeldCandidate(candidate, future));
		}
		else {
			addCandidate(candidate, future);
		}

		return future;
	}

	/**
	 * Adds the remote candidates held so far, once there is a remote
	 * description.
	 */
	void releaseHeldCandidates() {
		List<HeldCandidate> held = new ArrayList<>(heldCandidates);
		heldCandidates.clear();

		for (HeldCandidate candidate : held) {
			addCandidate(candidate.candidate, candidate.future);
		}
	}

	/**
	 * Waits until gathering is complete, or the gathering timeout passed.
	 */
	CompletableFuture<Void> waitForGathering() {
		if (connection.get().getIceGatheringState() == RTCIceGatheringState.COMPLETE) {
			return CompletableFuture.completedFuture(null);
		}

		CompletableFuture<Void> future = chain.step();
		gatheringWaiters.add(future);

		// A STUN or TURN server that cannot be reached holds up the end of
		// gathering for long; the description then goes with what there is.
		try {
			timer.schedule(() -> control.execute(() -> {
				if (gatheringWaiters.remove(future)) {
					future.complete(null);
				}
			}), Timeouts.toMillis(gatheringTimeout), TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException e) {
			// The runtime is closing and has no timer left to wait with.
			gatheringWaiters.remove(future);
			future.complete(null);
		}

		return future;
	}

	/**
	 * Ends the waiting for gathering, which WebRTC reports complete.
	 */
	void gatheringComplete() {
		List<CompletableFuture<Void>> waiters = new ArrayList<>(gatheringWaiters);
		gatheringWaiters.clear();

		waiters.forEach(waiter -> waiter.complete(null));
	}

	/**
	 * Fails the waiting for gathering and the candidates held, once the peer
	 * is closing.
	 */
	void close(PeerClosedException error) {
		for (CompletableFuture<Void> waiter : gatheringWaiters) {
			waiter.completeExceptionally(error);
		}
		gatheringWaiters.clear();

		for (HeldCandidate candidate : heldCandidates) {
			candidate.future.completeExceptionally(error);
		}
		heldCandidates.clear();
	}

	private void addCandidate(RTCIceCandidate candidate, CompletableFuture<Void> future) {
		connection.get().addIceCandidate(candidate, new AddIceCandidateObserver() {

			@Override
			public void onSuccess() {
				control.execute(() -> future.complete(null));
			}

			@Override
			public void onFailure(String error) {
				control.execute(() -> {
					if (ignoringOffer.getAsBoolean()) {
						// A candidate of an offer this peer ignored.
						future.complete(null);
					}
					else {
						future.completeExceptionally(new NegotiationException("Adding a remote candidate failed: "
								+ error));
					}
				});
			}
		});
	}

	private SetSessionDescriptionObserver descriptionObserver(CompletableFuture<Void> future, String message) {
		return new SetSessionDescriptionObserver() {

			@Override
			public void onSuccess() {
				control.execute(() -> future.complete(null));
			}

			@Override
			public void onFailure(String error) {
				control.execute(() -> future.completeExceptionally(new NegotiationException(message + ": " + error)));
			}
		};
	}

	private CreateSessionDescriptionObserver createObserver(
			CompletableFuture<RTCSessionDescription> future, String message) {
		return new CreateSessionDescriptionObserver() {

			@Override
			public void onSuccess(RTCSessionDescription description) {
				control.execute(() -> future.complete(description));
			}

			@Override
			public void onFailure(String error) {
				control.execute(() -> future.completeExceptionally(new NegotiationException(message + ": " + error)));
			}
		};
	}



	private static final class HeldCandidate {

		final RTCIceCandidate candidate;

		final CompletableFuture<Void> future;


		HeldCandidate(RTCIceCandidate candidate, CompletableFuture<Void> future) {
			this.candidate = candidate;
			this.future = future;
		}
	}
}
