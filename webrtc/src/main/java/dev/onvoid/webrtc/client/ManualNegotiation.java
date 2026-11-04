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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCRtpTransceiverDirection;
import dev.onvoid.webrtc.RTCRtpTransceiverInit;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;
import dev.onvoid.webrtc.media.MediaType;

/**
 * The negotiation of a peer driven by hand: the {@link Negotiator} of the
 * application, and the finer steps WHIP and WHEP take on top of it.
 * <p>
 * Any thread may call these methods. Each posts an operation to the peer's
 * {@link OperationChain}, so that the operation runs on the control executor,
 * and hands back a future that completes on the event executor.
 */
final class ManualNegotiation implements Negotiator {

	private final PeerImpl peer;

	private final PeerOptions options;

	private final OperationChain chain;

	private final NegotiationSteps steps;

	private final RemoteTracks tracks;

	private final CodecPreferences codecs;


	ManualNegotiation(PeerImpl peer, PeerOptions options, OperationChain chain, NegotiationSteps steps,
			RemoteTracks tracks, CodecPreferences codecs) {
		this.peer = peer;
		this.options = options;
		this.chain = chain;
		this.steps = steps;
		this.tracks = tracks;
		this.codecs = codecs;
	}

	@Override
	public CompletableFuture<RTCSessionDescription> createOffer() {
		return createOffer(Gathering.TRICKLE);
	}

	@Override
	public CompletableFuture<RTCSessionDescription> createOffer(Gathering gathering) {
		requireNonNull(gathering, "gathering");

		return run(() -> steps.createOffer()
				.thenCompose(steps::setLocal)
				.thenCompose(ignored -> gathered(gathering)));
	}

	@Override
	public CompletableFuture<RTCSessionDescription> answer(RTCSessionDescription offer) {
		return answer(offer, Gathering.TRICKLE);
	}

	@Override
	public CompletableFuture<RTCSessionDescription> answer(RTCSessionDescription offer, Gathering gathering) {
		requireNonNull(offer, "offer");
		requireNonNull(gathering, "gathering");

		if (offer.sdpType != RTCSdpType.OFFER) {
			throw new IllegalArgumentException("Expected an offer, got " + offer.sdpType);
		}

		return run(() -> steps.setRemote(offer)
				.thenCompose(ignored -> {
					steps.releaseHeldCandidates();
					return steps.createAnswer();
				})
				.thenCompose(steps::setLocal)
				.thenCompose(ignored -> gathered(gathering)));
	}

	@Override
	public CompletableFuture<Void> applyAnswer(RTCSessionDescription answer) {
		requireNonNull(answer, "answer");

		if (answer.sdpType != RTCSdpType.ANSWER && answer.sdpType != RTCSdpType.PR_ANSWER) {
			throw new IllegalArgumentException("Expected an answer, got " + answer.sdpType);
		}

		return run(() -> steps.setRemote(answer).thenRun(steps::releaseHeldCandidates));
	}

	@Override
	public CompletableFuture<Void> addCandidate(RTCIceCandidate candidate) {
		requireNonNull(candidate, "candidate");

		return run(() -> steps.addRemoteCandidate(candidate));
	}

	/**
	 * Creates an offer without applying it, so that the configuration can
	 * still change before candidates are gathered for it, as WHIP and WHEP do
	 * with the ICE servers the endpoint names in its answer.
	 *
	 * @return a future with the offer, which {@link #applyLocal} applies.
	 */
	CompletableFuture<RTCSessionDescription> offer() {
		return run(steps::createOffer);
	}

	/**
	 * Applies a description this peer created, which starts the gathering of
	 * candidates for it.
	 */
	CompletableFuture<Void> applyLocal(RTCSessionDescription description) {
		return run(() -> steps.setLocal(description));
	}

	/**
	 * Makes every transceiver send only, as a WHIP client's offer should be
	 * (RFC 9725, 4.2). Ordered with the negotiation steps, so it takes effect
	 * on the next offer.
	 */
	CompletableFuture<Void> sendOnly() {
		return run(() -> {
			for (RTCRtpTransceiver transceiver : peer.connection().getTransceivers()) {
				try {
					if (!transceiver.stopped()) {
						transceiver.setDirection(RTCRtpTransceiverDirection.SEND_ONLY);
					}
				}
				finally {
					transceiver.dispose();
				}
			}

			return CompletableFuture.completedFuture(null);
		});
	}

	/**
	 * Adds video that only sends, without a track, to the stream of the
	 * tracks added without stream ids, for endpoints that take no offer
	 * without video. Ordered with the negotiation steps.
	 */
	CompletableFuture<Void> addVideoWithoutTrack() {
		return run(() -> {
			RTCRtpTransceiverInit init = new RTCRtpTransceiverInit();
			init.direction = RTCRtpTransceiverDirection.SEND_ONLY;
			init.streamIds.add(peer.defaultStreamId);

			peer.connection().addTransceiver(MediaType.VIDEO, init).dispose();

			return CompletableFuture.completedFuture(null);
		});
	}

	/**
	 * Negotiates only the codecs of the given names, in that order, for each
	 * kind of media given, together with the companion codecs WebRTC pairs
	 * with them. This holds for the transceivers there are, and for those an
	 * offer adds later, as a counter-offer this peer answers instead of its
	 * own. Ordered with the negotiation steps, so it takes effect on the next
	 * offer or answer.
	 *
	 * @param names the codec names of each kind, such as H264 or opus.
	 *
	 * @return a future that fails if WebRTC has no codec of a name.
	 */
	CompletableFuture<Void> preferCodecs(Map<MediaKind, List<String>> names) {
		return run(() -> {
			codecs.prefer(names);

			return CompletableFuture.completedFuture(null);
		});
	}

	/**
	 * Gathers candidates from now on with the given ICE servers, unless the
	 * peer was built with servers of its own, which take precedence.
	 *
	 * @param servers the ICE servers; none leaves the configuration as it is.
	 *
	 * @return a future that fails if WebRTC refuses the servers.
	 */
	CompletableFuture<Void> useIceServers(List<RTCIceServer> servers) {
		if (servers.isEmpty() || !options.iceServers.isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}

		return run(() -> {
			try {
				RTCPeerConnection connection = peer.connection();
				RTCConfiguration config = connection.getConfiguration();
				config.iceServers = new ArrayList<>(servers);
				connection.setConfiguration(config);

				return CompletableFuture.completedFuture(null);
			}
			catch (RuntimeException e) {
				return CompletableFuture.failedFuture(new ConfigurationException("WebRTC refused the ICE servers "
						+ servers + " for " + peer.name + ": " + e.getMessage(), e));
			}
		});
	}

	/**
	 * Restarts ICE as WHIP and WHEP do, without a renegotiation: only the ICE
	 * information of a new offer goes to the remote peer, and the remote
	 * description keeps its offer/answer role, with the ICE information that
	 * comes back. After a server counter-offer, the new local credentials are
	 * applied in an answer to the updated remote offer.
	 * An exchange that fails takes the new offer back, and the ICE session
	 * before goes on.
	 *
	 * @param exchange sends the ICE information of the new offer, and returns
	 *                 how to change the remote answer.
	 *
	 * @return a future that completes once the new ICE information is applied.
	 */
	CompletableFuture<Void> restartIce(
			Function<RTCSessionDescription, CompletableFuture<UnaryOperator<String>>> exchange) {
		return restartIce(exchange, (value, error) -> { });
	}

	CompletableFuture<Void> restartIce(
			Function<RTCSessionDescription, CompletableFuture<UnaryOperator<String>>> exchange,
			BiConsumer<Void, Throwable> finished) {
		return run(() -> {
			RTCPeerConnection connection = peer.connection();
			RTCSessionDescription remote = connection.getRemoteDescription();

			if (connection.getSignalingState() != RTCSignalingState.STABLE || remote == null
					|| (remote.sdpType != RTCSdpType.ANSWER && remote.sdpType != RTCSdpType.OFFER)) {
				return CompletableFuture.failedFuture(new NegotiationException("The peer " + peer.name
						+ " has no stable description to restart ICE with"));
			}

			connection.restartIce();

			if (remote.sdpType == RTCSdpType.OFFER) {
				// Keep the server's offerer role (including its DTLS role and
				// media sections). Create an offer only to obtain fresh local
				// ICE credentials; do not apply it or change signaling roles.
				return steps.createOffer().thenCompose(offer -> onControl(exchange.apply(offer))
						.thenCompose(change -> steps.setRemote(new RTCSessionDescription(
								RTCSdpType.OFFER, change.apply(remote.sdp))))
						.thenCompose(ignored -> {
							// Reapplying the server's offer can create transceivers
							// for media the original answer rejected.
							stopTransceivers(midsNotReceived(remote.sdp));
							return steps.createAnswer();
						})
						.thenCompose(answer -> steps.setLocal(new RTCSessionDescription(RTCSdpType.ANSWER,
								HttpIce.Fragment.of(offer.sdp).applyCredentialsTo(answer.sdp)))))
						.handle((applied, error) -> {
							if (error == null) {
								return CompletableFuture.<Void>completedFuture(null);
							}
							CompletableFuture<Void> failed = CompletableFuture.failedFuture(PeerImpl.unwrap(error));
							if (peer.isClosed() || connection.getSignalingState() != RTCSignalingState.HAVE_REMOTE_OFFER) {
								return failed;
							}
							return steps.setRemote(new RTCSessionDescription(RTCSdpType.ROLLBACK, ""))
									.handle((ignored, rollbackError) -> null)
									.thenCompose(ignored -> failed);
						}).thenCompose(result -> result);
			}

			return steps.createOffer()
					.thenCompose(offer -> steps.setLocal(offer).thenApply(ignored -> offer))
					.thenCompose(offer -> onControl(exchange.apply(offer)))
					.thenCompose(change -> {
						RTCSessionDescription answer = connection.getRemoteDescription();

						return steps.setRemote(new RTCSessionDescription(RTCSdpType.ANSWER, change.apply(answer.sdp)));
					})
					.handle((applied, error) -> {
						if (error == null) {
							return CompletableFuture.<Void>completedFuture(null);
						}

						CompletableFuture<Void> failed = CompletableFuture.failedFuture(PeerImpl.unwrap(error));

						if (peer.isClosed() || connection.getSignalingState() == RTCSignalingState.STABLE) {
							return failed;
						}

						// Back to the ICE session before, which the server keeps.
						return steps.setLocal(new RTCSessionDescription(RTCSdpType.ROLLBACK, ""))
								.handle((ignored, rollbackError) -> null)
								.thenCompose(ignored -> failed);
					})
					.thenCompose(result -> result);
		}, finished);
	}

	/**
	 * Takes this peer's own offer back and answers the given one instead, as
	 * a WHEP player does when the server counters its offer. Unlike {@link
	 * Negotiator#answer}, which fails while an offer of this peer is out.
	 * <p>
	 * The answer receives only the kinds of media the peer was built to
	 * receive: the audio or video m-lines of other kinds are rejected, with
	 * port 0. An offer without any m-line of those kinds fails.
	 *
	 * @param offer     the offer that replaces this peer's own.
	 * @param gathering when the answer is handed over.
	 *
	 * @return a future with the answer, applied as the local description.
	 */
	CompletableFuture<RTCSessionDescription> answerInstead(RTCSessionDescription offer, Gathering gathering) {
		Set<String> rejected;

		try {
			rejected = midsNotReceived(offer.sdp);
		}
		catch (NegotiationException e) {
			return peer.toPublic(CompletableFuture.failedFuture(e));
		}

		return run(() -> {
			// Before the offer is applied, which reports the tracks of the
			// m-lines that are then rejected.
			for (MediaKind kind : MediaKind.values()) {
				if (!options.receive.contains(kind)) {
					tracks.decline(kind);
				}
			}

			// An offer created but not applied yet needs no taking back.
			CompletableFuture<Void> stable = peer.connection().getSignalingState() == RTCSignalingState.STABLE
					? CompletableFuture.completedFuture(null)
					: steps.setLocal(new RTCSessionDescription(RTCSdpType.ROLLBACK, ""));

			return stable
					.thenCompose(ignored -> steps.setRemote(offer))
					.thenCompose(ignored -> {
						stopTransceivers(rejected);
						// The offer made transceivers of its own.
						codecs.apply();
						steps.releaseHeldCandidates();
						return steps.createAnswer();
					})
					.thenCompose(steps::setLocal)
					.thenCompose(ignored -> gathered(gathering));
		});
	}

	/**
	 * @return a future that completes as the given one, but on the control
	 * executor, or fails once the peer is closed.
	 */
	private <T> CompletableFuture<T> onControl(CompletableFuture<T> future) {
		CompletableFuture<T> result = chain.step();

		future.whenComplete((value, error) -> peer.control.execute(() -> PeerImpl.complete(result, value, error),
				() -> result.completeExceptionally(peer.closedError())));

		return result;
	}

	/**
	 * Finds the audio and video m-lines of an offer whose kind this peer does
	 * not receive.
	 *
	 * @return their mids.
	 *
	 * @throws NegotiationException if the offer has no open m-line of a kind
	 *                              this peer receives.
	 */
	private Set<String> midsNotReceived(String sdp) {
		Set<String> rejected = new HashSet<>();
		boolean received = false;
		MediaKind kind = null;

		for (String line : sdp.split("\r?\n")) {
			if (line.startsWith("m=")) {
				String[] fields = line.substring(2).split(" ");
				boolean open = fields.length > 1 && !fields[1].equals("0");

				kind = !open ? null
						: fields[0].equals("audio") ? MediaKind.AUDIO
						: fields[0].equals("video") ? MediaKind.VIDEO
						: null;
				received |= kind != null && options.receive.contains(kind);
			}
			else if (line.startsWith("a=mid:") && kind != null && !options.receive.contains(kind)) {
				rejected.add(line.substring("a=mid:".length()).trim());
			}
		}

		if (!received) {
			throw new NegotiationException("The offer of the remote peer of " + peer.name
					+ " holds none of the media it receives, " + options.receive);
		}

		return rejected;
	}

	/**
	 * Stops the transceivers of the given mids, whose m-lines the next
	 * answer then rejects.
	 */
	private void stopTransceivers(Set<String> mids) {
		if (mids.isEmpty()) {
			return;
		}

		for (RTCRtpTransceiver transceiver : peer.connection().getTransceivers()) {
			try {
				if (mids.contains(transceiver.getMid()) && !transceiver.stopped()) {
					// At once: an answer rejects only the m-lines of stopped
					// transceivers, and keeps those still stopping, inactive.
					transceiver.stopImmediately();
				}
			}
			finally {
				transceiver.dispose();
			}
		}
	}

	private CompletableFuture<RTCSessionDescription> gathered(Gathering gathering) {
		CompletableFuture<Void> ready = gathering == Gathering.COMPLETE
				? steps.waitForGathering()
				: CompletableFuture.completedFuture(null);

		return ready.thenApply(ignored -> peer.connection().getLocalDescription());
	}

	private <T> CompletableFuture<T> run(Supplier<CompletableFuture<T>> operation) {
		return run(operation, (value, error) -> { });
	}

	private <T> CompletableFuture<T> run(Supplier<CompletableFuture<T>> operation,
			BiConsumer<T, Throwable> finished) {
		CompletableFuture<T> result = new CompletableFuture<>();

		peer.control.execute(() -> chain.enqueue(operation, finished)
				.whenComplete((value, error) -> PeerImpl.complete(result, value, error)));

		return peer.toPublic(result);
	}
}
