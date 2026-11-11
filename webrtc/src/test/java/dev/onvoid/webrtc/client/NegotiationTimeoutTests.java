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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCSignalingState;

import org.junit.jupiter.api.Test;

class NegotiationTimeoutTests extends ClientTestBase {

	private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(37);

	private static final String CANDIDATE_ADDRESS = "203.0.113.7";

	private static final String CANDIDATE = "candidate:1 1 udp 2122260223 " + CANDIDATE_ADDRESS + " 49203 typ host";

	@Test
	void lateRestartAnswerDiscarded() throws Exception {
		lateRestartAnswer(false);
	}

	@Test
	void lateCounterOfferDiscarded() throws Exception {
		lateRestartAnswer(true);
	}

	private void lateRestartAnswer(boolean remoteOffer) throws Exception {
		ManualExecutor control = new ManualExecutor();
		ManualExecutor events = new ManualExecutor();
		ManualTimer timer = new ManualTimer();
		WebRtc client = runtime(control, events, timer);
		try {
			PeerImpl peer = open(client, control, events, remoteOffer);
			CompletableFuture<UnaryOperator<String>> late = new CompletableFuture<>();
			AtomicBoolean firstSent = new AtomicBoolean();
			AtomicBoolean applied = new AtomicBoolean();
			CompletableFuture<Void> first = peer.manual.restartIce(offer -> {
				firstSent.set(true);
				return late;
			});
			control.runUntil(firstSent::get, TIMEOUT_SECONDS);

			CompletableFuture<UnaryOperator<String>> answer = new CompletableFuture<>();
			AtomicBoolean nextSent = new AtomicBoolean();
			CompletableFuture<Void> next = peer.manual.restartIce(offer -> {
				nextSent.set(true);
				return answer;
			});
			assertEquals(1, timer.fire(OPERATION_TIMEOUT));
			control.runUntil(nextSent::get, TIMEOUT_SECONDS);
			events.runAll();
			assertInstanceOf(TimeoutException.class,
					assertThrows(ExecutionException.class, () -> await(first)).getCause());

			// The old answer arrives while the next restart owns signaling.
			late.complete(sdp -> {
				applied.set(true);
				return sdp;
			});
			control.runAll();
			assertFalse(applied.get());
			assertFalse(next.isDone());
			answer.complete(UnaryOperator.identity());
			drive(next, control, events);
			assertEquals(RTCSignalingState.STABLE,
					drive(peer.withNative(connection -> connection.getSignalingState()), control, events));
		}
		finally {
			close(client, control, events);
		}
	}

	@Test
	void restartCleanupPrecedesEvents() throws Exception {
		ManualExecutor control = new ManualExecutor();
		ManualExecutor events = new ManualExecutor();
		WebRtc client = runtime(control, events, new ManualTimer());
		CountDownLatch release = new CountDownLatch(1);
		HttpIce ice = null;
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			PeerImpl peer = open(client, control, events, false);
			String local = drive(peer.withNative(connection -> connection.getLocalDescription().sdp), control, events);
			String remote = drive(peer.withNative(connection -> connection.getRemoteDescription().sdp), control, events);
			ice = new HttpIce(client, HttpClient.newHttpClient(), peer, null, Duration.ofSeconds(30));
			ice.attach(endpoint.uri(), "\"initial\"", local);
			endpoint.iceRestarts = fragment -> HttpIce.Fragment.of(remote).text;
			endpoint.iceRestartStatus = 503;
			CompletableFuture<Void> first = ice.restart();
			// A second queued operation can start before first's public future
			// is delivered. Its request is held while those events are drained.
			AtomicBoolean secondEntered = new AtomicBoolean();
			AtomicReference<String> secondOffer = new AtomicReference<>();
			CompletableFuture<Void> ready = new CompletableFuture<>();
			peer.withNative(connection -> {
				ready.complete(null);
				return null;
			});
			control.runUntil(ready::isDone, TIMEOUT_SECONDS);
			assertFalse(first.isDone());
			endpoint.iceRestartStatus = 200;
			endpoint.iceRestarts = fragment -> {
				secondOffer.set(fragment);
				secondEntered.set(true);
				try {
					assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
				return HttpIce.Fragment.of(remote).text;
			};
			CompletableFuture<Void> second = ice.restart();
			control.runUntil(secondEntered::get, TIMEOUT_SECONDS);
			events.runAll();
			assertThrows(ExecutionException.class, () -> await(first));
			release.countDown();
			drive(second, control, events);
			// The failed restart's event must not restore old credentials or
			// clear the candidates gathered for the successful restart.
			control.runUntil(() -> endpoint.requests.stream().anyMatch(
					request -> "\"restart-1\"".equals(request.ifMatch)), TIMEOUT_SECONDS);
			TestHttpEndpoint.Request candidates = endpoint.awaitRequest(
					request -> "\"restart-1\"".equals(request.ifMatch), "restart candidates", TIMEOUT_SECONDS);
			assertEquals(HttpIce.Fragment.parse(secondOffer.get()).ufrag,
					HttpIce.Fragment.parse(candidates.body).ufrag);
		}
		finally {
			release.countDown();
			if (ice != null) {
				ice.close();
			}
			close(client, control, events);
		}
	}

	@Test
	void candidateBeforeOffer() throws Exception {
		ManualExecutor control = new ManualExecutor();
		ManualExecutor events = new ManualExecutor();
		ManualTimer timer = new ManualTimer();
		WebRtc client = runtime(control, events, timer);
		try {
			RemoteSignaling signaling = new RemoteSignaling();
			// Nothing to receive, so that it makes no offer of its own to collide.
			PeerImpl peer = (PeerImpl) drive(CompletableFuture.supplyAsync(() -> client.createPeer(
					PeerOptions.builder().signaling(signaling).build())), control, events);

			// The timeout never fires here, so only the candidate letting the
			// chain go on can bring the offer to its answer.
			signaling.inbound.accept(SignalMessage.of(new RTCIceCandidate("0", 0, CANDIDATE)));
			signaling.inbound.accept(SignalMessage.of(remoteOffer()));
			control.runUntil(() -> {
				events.runAll();
				return signaling.sent.stream().anyMatch(message -> message.type() == SignalMessage.Type.ANSWER);
			}, TIMEOUT_SECONDS);

			assertTrue(remoteSdp(peer, control, events).contains(CANDIDATE_ADDRESS));
			assertEquals(0, timer.scheduled(OPERATION_TIMEOUT));
		}
		finally {
			close(client, control, events);
		}
	}

	@Test
	void manualCandidateBeforeOffer() throws Exception {
		ManualExecutor control = new ManualExecutor();
		ManualExecutor events = new ManualExecutor();
		ManualTimer timer = new ManualTimer();
		WebRtc client = runtime(control, events, timer);
		try {
			PeerImpl peer = manualPeer(client, control, events);

			// Done once held, so a caller may wait for it before it answers.
			drive(peer.negotiator().addCandidate(new RTCIceCandidate("0", 0, CANDIDATE)), control, events);
			drive(peer.negotiator().answer(remoteOffer()), control, events);

			assertTrue(remoteSdp(peer, control, events).contains(CANDIDATE_ADDRESS));
			assertEquals(0, timer.scheduled(OPERATION_TIMEOUT));
		}
		finally {
			close(client, control, events);
		}
	}

	@Test
	void rejectedHeldCandidateReported() throws Exception {
		ManualExecutor control = new ManualExecutor();
		ManualExecutor events = new ManualExecutor();
		WebRtc client = runtime(control, events, new ManualTimer());
		try {
			PeerImpl peer = manualPeer(client, control, events);

			// The offer has no m-line of this mid.
			drive(peer.negotiator().addCandidate(new RTCIceCandidate("9", 9, CANDIDATE)), control, events);
			drive(peer.negotiator().answer(remoteOffer()), control, events);

			control.runUntil(() -> {
				events.runAll();
				return !errors.isEmpty();
			}, TIMEOUT_SECONDS);

			assertInstanceOf(NegotiationException.class, errors.remove(0));
		}
		finally {
			close(client, control, events);
		}
	}

	private PeerImpl manualPeer(WebRtc client, ManualExecutor control, ManualExecutor events) throws Exception {
		return (PeerImpl) drive(CompletableFuture.supplyAsync(() -> client.createPeer(PeerOptions.builder()
				.negotiation(Negotiation.MANUAL).receive(MediaKind.AUDIO).build())), control, events);
	}

	private RTCSessionDescription remoteOffer() throws Exception {
		return await(rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL)
				.receive(MediaKind.AUDIO).build()).negotiator().createOffer());
	}

	private static String remoteSdp(PeerImpl peer, ManualExecutor control, ManualExecutor events) throws Exception {
		return drive(peer.withNative(connection -> connection.getRemoteDescription().sdp), control, events);
	}

	private WebRtc runtime(ManualExecutor control, ManualExecutor events, ManualTimer timer) {
		return WebRtc.builder().audio(AudioMode.HEADLESS).controlPool(control).eventExecutor(events)
				.timer(timer).operationTimeout(OPERATION_TIMEOUT).errorHandler(errors::add).build();
	}

	private PeerImpl open(WebRtc client, ManualExecutor control, ManualExecutor events, boolean remoteOffer)
			throws Exception {
		CompletableFuture<Peer> opening = CompletableFuture.supplyAsync(() -> client.createPeer(PeerOptions.builder()
				.negotiation(Negotiation.MANUAL).receive(MediaKind.AUDIO).build()));
		PeerImpl peer = (PeerImpl) drive(opening, control, events);
		Peer server = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL)
				.receive(MediaKind.AUDIO).build());
		if (remoteOffer) {
			RTCSessionDescription offer = await(server.negotiator().createOffer(Gathering.COMPLETE));
			RTCSessionDescription answer = drive(peer.negotiator().answer(offer, Gathering.COMPLETE), control, events);
			await(server.negotiator().applyAnswer(answer));
		}
		else {
			RTCSessionDescription offer = drive(peer.negotiator().createOffer(Gathering.COMPLETE), control, events);
			RTCSessionDescription answer = await(server.negotiator().answer(offer, Gathering.COMPLETE));
			drive(peer.negotiator().applyAnswer(answer), control, events);
		}
		return peer;
	}

	private static <T> T drive(CompletableFuture<T> future, ManualExecutor control, ManualExecutor events)
			throws Exception {
		control.runUntil(() -> {
			events.runAll();
			return future.isDone();
		}, TIMEOUT_SECONDS);
		return await(future);
	}

	private static void close(WebRtc client, ManualExecutor control, ManualExecutor events) throws Exception {
		drive(client.closeAsync(), control, events);
	}



	/** The signaling of a peer, with the test as the remote side. */
	private static final class RemoteSignaling implements Signaling {

		final List<SignalMessage> sent = new CopyOnWriteArrayList<>();

		volatile Consumer<SignalMessage> inbound;


		@Override
		public void send(SignalMessage message) {
			sent.add(message);
		}

		@Override
		public Subscription attach(Consumer<SignalMessage> inbound) {
			this.inbound = inbound;
			return () -> { };
		}
	}
}
