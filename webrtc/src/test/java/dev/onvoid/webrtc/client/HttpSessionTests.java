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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpSessionTests extends ClientTestBase {

	/** Longer than any test takes: the session times out only when a test fires it. */
	private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(25);

	private ScheduledExecutorService feeder;

	private CustomVideo video;


	@BeforeEach
	void feedVideo() {
		video = rtc.media().customVideo("video");
		feeder = Executors.newSingleThreadScheduledExecutor();
		feeder.scheduleAtFixedRate(() -> {
			NativeI420Buffer buffer = NativeI420Buffer.allocate(160, 120);
			ByteBuffer y = buffer.getDataY();
			for (int i = 0; i < y.capacity(); i++) {
				y.put(i, (byte) i);
			}
			VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
			video.push(frame);
			frame.release();
		}, 0, 33, TimeUnit.MILLISECONDS);
	}

	@AfterEach
	void stopFeeding() throws InterruptedException {
		feeder.shutdownNow();
		feeder.awaitTermination(5, TimeUnit.SECONDS);
	}

	@Test
	void whepPlays() throws Exception {
		Peer[] server = new Peer[1];

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> {
			// The server's side sends the stream.
			server[0] = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
			server[0].addTrack(video);
			return answer(server[0], offer);
		}, 201)) {
			Whep.Session session = await(Whep.from(endpoint.uri()).bearerToken("secret").play(rtc));

			CompletableFuture<Integer> width = new CompletableFuture<>();
			session.peer().onTrack(track -> track.asVideo().onFrame(frame -> width.complete(frame.buffer.getWidth())));

			assertEquals(160, (int) await(width));
			assertEquals("Bearer secret", endpoint.requests.get(0).authorization);
			assertEquals(URI.create(endpoint.uri().resolve("/live/session/1").toString()), session.resource());

			await(session.closeAsync());

			assertEquals("DELETE /live/session/1", endpoint.requests.get(1).toString());
			assertEquals(PeerState.CLOSED, session.peer().state());
		}
	}

	@Test
	void whipPublishes() throws Exception {
		CompletableFuture<RemoteTrack> published = new CompletableFuture<>();

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> {
			// The server's side receives the stream.
			Peer server = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
			server.onTrack(published::complete);
			return answer(server, offer);
		}, 201)) {
			Whip.Session session = await(Whip.to(endpoint.uri()).track(video).publish(rtc));

			assertEquals(MediaKind.VIDEO, await(published).kind());

			// Before the endpoint goes, which would fail the DELETE.
			await(session.closeAsync());
			assertEquals("DELETE /live/session/1", endpoint.requests.get(1).toString());
		}
	}

	@Test
	void refusedOfferLeavesNothing() throws Exception {
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 403)) {
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> Whep.from(endpoint.uri()).play(rtc).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			assertInstanceOf(WebRtcException.class, failed.getCause());
			assertTrue(failed.getCause().getMessage().contains("403"), failed.getCause().getMessage());

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (!rtc.peers().isEmpty() && System.nanoTime() < deadline) {
				Thread.sleep(50);
			}
			assertTrue(rtc.peers().isEmpty(), () -> "Peers left: " + rtc.peers());
		}
	}

	@Test
	void foreignTrackLeavesNothing() throws Exception {
		WebRtc other = clientRuntime(UnaryOperator.identity());

		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Whip.Builder whip = Whip.to(endpoint.uri()).track(video).track(other.media().customVideo("other"));

			assertThrows(IllegalArgumentException.class, () -> whip.publish(rtc));
			assertTrue(rtc.peers().isEmpty(), () -> "Peers left: " + rtc.peers());
			assertTrue(endpoint.requests.isEmpty());
		}
		finally {
			other.close();
		}
	}

	@Test
	void failedStartClosesPeer() throws Exception {
		// The session's deadline cannot be scheduled, after the peer was created.
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));

		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			CustomVideo track = client.media().customVideo("video");
			timer.shutdown();

			assertThrows(RejectedExecutionException.class,
					() -> Whip.to(endpoint.uri()).track(track).publish(client));
			assertThrows(RejectedExecutionException.class, () -> Whep.from(endpoint.uri()).play(client));

			for (Peer peer : client.peers()) {
				PeerImpl impl = (PeerImpl) peer;
				assertTrue(impl.isClosing(), () -> "Not closing: " + peer);
				impl.closeInternal().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			assertTrue(client.peers().isEmpty(), () -> "Peers left: " + client.peers());
		}
		finally {
			client.close();
		}
	}

	@Test
	void timeoutAbortsRequest() throws Exception {
		// The session times out while the server holds the answer. The request
		// is cut off then, rather than holding its thread until the server
		// answers.
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			endpoint.stallAnswers();
			CompletableFuture<Whep.Session> session = Whep.from(endpoint.uri()).timeout(SESSION_TIMEOUT).play(client);

			endpoint.awaitRequest("POST /live/endpoint", TIMEOUT_SECONDS);
			assertEquals(1, timer.fire(SESSION_TIMEOUT));

			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session));
			assertTrue(failed.getCause().getMessage().contains("within"), failed.getCause().getMessage());
			endpoint.awaitHangUp(TIMEOUT_SECONDS);
		}
		finally {
			client.close();
		}
	}

	@Test
	void tricklingAnswerTimesOut() throws Exception {
		// Each byte of the answer comes well within the timeout, and the answer
		// never ends. The session's own deadline is on a timer that does not
		// fire, so only the deadline of the request as a whole can end it.
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			endpoint.trickleAnswers();

			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whep.from(endpoint.uri()).timeout(Duration.ofSeconds(2)).play(client)));

			assertInstanceOf(HttpTimeoutException.class, failed.getCause().getCause(),
					failed.getCause()::getMessage);
			endpoint.awaitHangUp(TIMEOUT_SECONDS);
			// The server named the session before the answer timed out.
			endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);
		}
		finally {
			client.close();
		}
	}

	@Test
	void conflictRetriedForWhep() throws Exception {
		// The stream has yet to start, and the endpoint says to come back at
		// once. The offer goes again after the shortest wait, on the timer.
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));
		Duration wait = Duration.ofMillis(HttpCall.MIN_RETRY_MILLIS);

		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.refuseAnswers(1, 409, "0");
			CompletableFuture<Whep.Session> session = Whep.from(endpoint.uri()).timeout(SESSION_TIMEOUT).play(client);

			awaitScheduled(timer, wait);
			assertEquals(1, endpoint.count("POST /live/endpoint"));
			assertEquals(1, timer.fire(wait));

			await(await(session).closeAsync());
			assertEquals(2, endpoint.count("POST /live/endpoint"));
		}
		finally {
			client.close();
		}
	}

	@Test
	void unavailableRetriedForWhip() throws Exception {
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));
		Duration wait = Duration.ofSeconds(2);

		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			endpoint.refuseAnswers(1, 503, "2");
			CompletableFuture<Whip.Session> session = Whip.to(endpoint.uri())
					.track(client.media().customVideo("video"))
					.timeout(SESSION_TIMEOUT)
					.publish(client);

			awaitScheduled(timer, wait);
			assertEquals(1, timer.fire(wait));

			await(await(session).closeAsync());
			assertEquals(2, endpoint.count("POST /live/endpoint"));
		}
		finally {
			client.close();
		}
	}

	@Test
	void conflictFailsWhip() throws Exception {
		// 409 means nothing particular to WHIP, so it is no reason to retry.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			endpoint.refuseAnswers(1, 409, "1");

			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whip.to(endpoint.uri()).track(video).publish(rtc)));

			assertTrue(failed.getCause().getMessage().contains("409"), failed.getCause().getMessage());
			assertEquals(1, endpoint.count("POST /live/endpoint"));
		}
	}

	@Test
	void retryBeyondTimeoutFails() throws Exception {
		// Coming back in a minute would miss the session's timeout.
		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.refuseAnswers(1, 503, "60");

			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whep.from(endpoint.uri()).timeout(SESSION_TIMEOUT).play(rtc)));

			assertTrue(failed.getCause().getMessage().contains("503"), failed.getCause().getMessage());
			assertEquals(1, endpoint.count("POST /live/endpoint"));
		}
	}

	@Test
	void cancelEndsRetryWait() throws Exception {
		// Cancelled while it waits to send the offer again: it never does.
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));
		Duration wait = Duration.ofSeconds(2);

		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.refuseAnswers(1, 503, "2");
			CompletableFuture<Whep.Session> session = Whep.from(endpoint.uri()).timeout(SESSION_TIMEOUT).play(client);

			awaitScheduled(timer, wait);
			assertTrue(session.cancel(true));

			assertEquals(0, timer.fire(wait));
			assertEquals(1, endpoint.count("POST /live/endpoint"));
		}
		finally {
			client.close();
		}
	}

	@Test
	void counterOfferAnswered() throws Exception {
		// The endpoint rejects the player's offer and makes its own, which the
		// player answers by PATCH to the session the endpoint named.
		List<String> answers = new CopyOnWriteArrayList<>();

		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", answers)) {
			Whep.Session session = await(Whep.from(endpoint.uri()).bearerToken("secret").play(rtc));

			CompletableFuture<Integer> width = new CompletableFuture<>();
			session.peer().onTrack(track -> track.asVideo().onFrame(frame -> width.complete(frame.buffer.getWidth())));

			assertEquals(160, (int) await(width));
			assertEquals(endpoint.uri().resolve("/live/session/1"), session.resource());
			assertEquals("PATCH /live/session/1", endpoint.requests.get(1).toString());
			assertEquals("Bearer secret", endpoint.requests.get(1).authorization);
			// WHEP asks the player's answer to receive only.
			assertTrue(answers.get(0).contains("a=recvonly"), answers.get(0));
			assertFalse(answers.get(0).contains("a=sendrecv"), answers.get(0));
			// With its candidates, gathered anew after the player's offer was
			// taken back.
			assertTrue(answers.get(0).contains("a=candidate"), answers.get(0));

			await(session.closeAsync());
			assertEquals(1, endpoint.count("DELETE /live/session/1"));
		}
	}

	@Test
	void counterOfferMediaRejected() throws Exception {
		// The endpoint offers audio and video; the player wants only video, and
		// rejects the audio m-line, with port 0, as WHEP allows.
		List<String> answers = new CopyOnWriteArrayList<>();
		CustomAudio audio = rtc.media().customAudio("audio");

		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", answers, video, audio)) {
			Whep.Session session = await(Whep.from(endpoint.uri()).receive(MediaKind.VIDEO).play(rtc));

			CompletableFuture<Integer> width = new CompletableFuture<>();
			session.peer().onTrack(track -> track.asVideo().onFrame(frame -> width.complete(frame.buffer.getWidth())));

			assertEquals(160, (int) await(width));
			assertTrue(answers.get(0).contains("\nm=audio 0 "), answers.get(0));
			assertFalse(answers.get(0).contains("\nm=video 0 "), answers.get(0));
			// The handler, which takes every track as video, saw no audio.
			assertTrue(errors.isEmpty(), () -> "Errors: " + errors);

			await(session.closeAsync());
		}
	}

	@Test
	void counterOfferWithoutWantedMediaFails() throws Exception {
		// At least one audio or video m-line has to be accepted.
		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", new ArrayList<>())) {
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whep.from(endpoint.uri()).receive(MediaKind.AUDIO).play(rtc)));

			assertTrue(failed.getCause().getMessage().contains("none of the media"), failed.getCause().getMessage());
			assertEquals(0, endpoint.count("PATCH /live/session/1"));
		}
	}

	@Test
	void counterOfferNeedsSdp() throws Exception {
		// Any other 406 is an error, as WHEP says.
		try (TestHttpEndpoint endpoint = counteringEndpoint("text/plain", new ArrayList<>())) {
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whep.from(endpoint.uri()).play(rtc)));

			assertTrue(failed.getCause().getMessage().contains("406"), failed.getCause().getMessage());
			assertEquals(0, endpoint.count("PATCH /live/session/1"));
		}
	}

	@Test
	void counterOfferRefusedForWhip() throws Exception {
		// WHIP knows no counter-offers.
		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", new ArrayList<>())) {
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whip.to(endpoint.uri()).track(video).publish(rtc)));

			assertTrue(failed.getCause().getMessage().contains("406"), failed.getCause().getMessage());
			assertEquals(0, endpoint.count("PATCH /live/session/1"));
		}
	}

	@Test
	void expiredCounterOfferFails() throws Exception {
		String expired = "application/sdp; valid-until=\"Wed, 09 Oct 2024 10:00:00 GMT\"";

		try (TestHttpEndpoint endpoint = counteringEndpoint(expired, new ArrayList<>())) {
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whep.from(endpoint.uri()).play(rtc)));

			assertTrue(failed.getCause().getMessage().contains("expired"), failed.getCause().getMessage());
			// Never sent, so the server created no session to delete.
			assertEquals(0, endpoint.count("PATCH /live/session/1"));
			assertEquals(0, endpoint.count("DELETE /live/session/1"));
		}
	}

	@Test
	void refusedCounterAnswerDeletes() throws Exception {
		// The session is the player's to delete once its answer went out.
		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", new ArrayList<>())) {
			endpoint.patchStatus = 400;

			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whep.from(endpoint.uri()).play(rtc)));

			assertTrue(failed.getCause().getMessage().contains("400"), failed.getCause().getMessage());
			endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);
		}
	}

	@Test
	void counterOfferExpiryRead() {
		long now = 1_000_000_000_000L;
		long date = ZonedDateTime.parse("Wed, 09 Oct 2024 10:00:00 GMT", DateTimeFormatter.RFC_1123_DATE_TIME)
				.toInstant().toEpochMilli();

		assertEquals(date, HttpSession.counterOfferExpiry(
				"application/sdp; valid-until=\"Wed, 09 Oct 2024 10:00:00 GMT\"", now));
		assertEquals(date, HttpSession.counterOfferExpiry(
				"application/sdp;Valid-Until=Wed, 09 Oct 2024 10:00:00 GMT", now));
		// Thirty seconds without one, or with one that is no date.
		assertEquals(now + 30_000, HttpSession.counterOfferExpiry("application/sdp", now));
		assertEquals(now + 30_000, HttpSession.counterOfferExpiry("application/sdp; valid-until=soon", now));
		assertEquals(now + 30_000, HttpSession.counterOfferExpiry(null, now));
	}

	@Test
	void redirectFollowed() throws Exception {
		// The endpoint sends the offer on to another, as WHIP and WHEP allow,
		// and the token goes along. The session is the one the other names.
		try (TestHttpEndpoint target = sendingEndpoint();
				TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			endpoint.redirectAnswers(307, target.uri().toString());

			Whep.Session session = await(Whep.from(endpoint.uri()).bearerToken("secret").play(rtc));

			assertEquals("Bearer secret", endpoint.requests.get(0).authorization);
			assertEquals("Bearer secret", target.requests.get(0).authorization);
			assertEquals(target.uri().resolve("/live/session/1"), session.resource());

			await(session.closeAsync());
			assertEquals(1, target.count("DELETE /live/session/1"));
			assertEquals(0, endpoint.count("DELETE /live/session/1"));
		}
	}

	@Test
	void redirectsLimited() throws Exception {
		// An endpoint that redirects to itself.
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			endpoint.redirectAnswers(308, endpoint.uri().toString());

			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whep.from(endpoint.uri()).play(rtc)));

			assertTrue(failed.getCause().getMessage().contains("redirected more than"),
					failed.getCause().getMessage());
			assertEquals(HttpCall.MAX_REDIRECTS + 1, endpoint.count("POST /live/endpoint"));
		}
	}

	@Test
	void foundNotFollowed() throws Exception {
		// 302 would turn the POST into a GET; WHIP and WHEP rule it out.
		try (TestHttpEndpoint target = sendingEndpoint();
				TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			endpoint.redirectAnswers(302, target.uri().toString());

			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whep.from(endpoint.uri()).play(rtc)));

			assertTrue(failed.getCause().getMessage().contains("302"), failed.getCause().getMessage());
			assertTrue(target.requests.isEmpty(), () -> "Requests: " + target.requests);
		}
	}

	@Test
	void tricklingDeleteTimesOut() throws Exception {
		// The deletion is bounded as the offer is: the head of its answer comes
		// a byte at a time and never ends, and the session's timeout ends it.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Peer peer = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
			HttpSession session = new Whep.Session(rtc, peer, endpoint.uri().resolve("/live/session/1"), null,
					Duration.ofSeconds(1));

			endpoint.trickleDeletes();

			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session.closeAsync()));

			assertInstanceOf(HttpTimeoutException.class, failed.getCause().getCause(),
					failed.getCause()::getMessage);
			endpoint.awaitHangUp(TIMEOUT_SECONDS);
			assertEquals(1, errors.size(), () -> "Errors: " + errors);
			errors.clear();
		}
	}

	@Test
	void slowDeleteDelaysNoPeer() throws Exception {
		// With one control thread, a deletion the server holds must not hold
		// up what the other peers do.
		WebRtc client = clientRuntime(builder -> builder.controlPool(Executors.newSingleThreadExecutor()));

		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			Whep.Session session = await(Whep.from(endpoint.uri()).play(client));
			Peer other = client.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
			CountDownLatch deleted = endpoint.holdDeletes();
			CompletableFuture<Void> closed = session.closeAsync();

			try {
				endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);
				await(other.stats());
				assertFalse(closed.isDone());
			}
			finally {
				deleted.countDown();
			}

			await(closed);
		}
		finally {
			client.close();
		}
	}

	@Test
	void shortTimeoutBoundsRequest() throws Exception {
		// Under a millisecond. The session's own deadline is on a timer that
		// does not fire, so only the request's deadline can end the wait.
		Duration timeout = Duration.ofNanos(1);
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			CountDownLatch answer = endpoint.holdAnswers();

			try {
				ExecutionException failed = assertThrows(ExecutionException.class,
						() -> await(Whep.from(endpoint.uri()).timeout(timeout).play(client)));

				assertInstanceOf(HttpTimeoutException.class, failed.getCause().getCause(),
						failed.getCause()::getMessage);
			}
			finally {
				answer.countDown();
			}
		}
		finally {
			client.close();
		}
	}

	@Test
	void cancelAbortsRequest() throws Exception {
		// The caller cancels while the offer is out. The peer must not go on to
		// connect, and the request must not wait for the answer.
		WebRtc client = clientRuntime(builder -> builder);

		try {
			CustomVideo track = client.media().customVideo("video");

			assertCancelAborts(client, uri -> Whip.to(uri).track(track).publish(client));
			assertCancelAborts(client, uri -> Whep.from(uri).play(client));
		}
		finally {
			client.close();
		}
	}

	@Test
	void unclaimedSessionClosed() throws Exception {
		// The caller cancels once the peer is connected, as the session is
		// made or right before. A session the future cannot take anymore is
		// closed, rather than left connected with no one to close it.
		WebRtc client = clientRuntime(builder -> builder);

		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			CountDownLatch answer = endpoint.holdAnswers();
			CompletableFuture<Whip.Session> session = Whip.to(endpoint.uri())
					.track(client.media().customVideo("video"))
					.publish(client);

			endpoint.awaitRequest("POST /live/endpoint", TIMEOUT_SECONDS);
			Peer peer = client.peers().get(0);
			CompletableFuture<Void> cancelled = peer.whenConnected().thenRun(() -> session.cancel(true));

			answer.countDown();
			await(cancelled);
			await(peer.whenClosed());
			endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);

			assertTrue(session.isCancelled());
			client.close();
			assertEquals(1, endpoint.count("DELETE /live/session/1"));
		}
		finally {
			client.close();
		}
	}

	@Test
	void timeoutAfterAnswerDeletes() throws Exception {
		// The session times out once the answer was read, before it is applied.
		// Who owns the resource then is the timeout, not the request.
		ManualExecutor control = new ManualExecutor();
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.controlPool(control).timer(timer));

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> answer(rtc.createPeer(PeerOptions.builder()
				.negotiation(Negotiation.MANUAL)
				.build()), offer), 201)) {
			CompletableFuture<CompletableFuture<Whep.Session>> playing = CompletableFuture.supplyAsync(
					() -> Whep.from(endpoint.uri()).timeout(SESSION_TIMEOUT).play(client));

			// The client's side up to the answer: the peer is created, and the
			// offer is created and posted. The answer, once read, hands its
			// application to the control executor, where it waits.
			control.runUntil(() -> endpoint.answered() > 0, TIMEOUT_SECONDS);
			control.awaitQueued(TIMEOUT_SECONDS);
			assertEquals(1, timer.fire(SESSION_TIMEOUT));
			control.runAll();

			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(await(playing)));
			assertTrue(failed.getCause().getMessage().contains("within"), failed.getCause().getMessage());
			endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);
			assertEquals(1, endpoint.count("DELETE /live/session/1"));
		}
		finally {
			closeStepping(client, control);
		}
	}

	@Test
	void closeAfterRuntimeReported() throws Exception {
		// A runtime that closed has no threads left to delete the session on.
		WebRtc client = clientRuntime(builder -> builder);

		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			Whep.Session session = await(Whep.from(endpoint.uri()).play(client));

			client.close();
			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session.closeAsync()));

			assertTrue(failed.getCause().getMessage().contains("left on the server"), failed.getCause().getMessage());
			assertEquals(0, endpoint.count("DELETE /live/session/1"));
			assertEquals(1, errors.size(), () -> "Errors: " + errors);
			assertTrue(errors.get(0).getMessage().contains("left on the server"), errors.get(0).getMessage());
			errors.clear();
		}
		finally {
			client.close();
		}
	}

	@Test
	void oversizedAnswerRefused() throws Exception {
		char[] huge = new char[2 << 20];
		Arrays.fill(huge, 'a');

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> new String(huge), 201)) {
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> Whep.from(endpoint.uri()).play(rtc).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			assertTrue(failed.getCause().getMessage().contains("larger than"), failed.getCause().getMessage());
			// The resource was named before the answer was refused.
			endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);
		}
	}

	@Test
	void failedDeleteReported() throws Exception {
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			endpoint.deleteStatus = 500;

			Whip.Session session = await(Whip.to(endpoint.uri()).track(video).publish(rtc));

			assertCloseFails(session, "500");
			// Closed all the same.
			assertEquals(PeerState.CLOSED, session.peer().state());
			assertDeleteReported("500");
		}
	}

	@Test
	void slowDeleteKeepsNoPeerOpen() throws Exception {
		// The peer closes while the server has yet to answer the deletion,
		// and the session is closed once both are done.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Whip.Session session = await(Whip.to(endpoint.uri()).track(video).publish(rtc));
			CountDownLatch deleted = endpoint.holdDeletes();
			CompletableFuture<Void> closed = session.closeAsync();

			try {
				endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);
				await(session.peer().whenClosed());
				assertFalse(closed.isDone());
			}
			finally {
				deleted.countDown();
			}

			await(closed);
		}
	}

	@Test
	void failedPeerCloseFails() throws Exception {
		IllegalStateException peerError = new IllegalStateException("The peer did not close");

		// Nothing to delete: the session fails as the peer did.
		HttpSession session = new Whep.Session(rtc, failingToClose(peerError), null, null, SESSION_TIMEOUT);
		ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session.closeAsync()));
		assertSame(peerError, failed.getCause());

		// Both fail: the deletion leads, and the peer's failure goes along.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			endpoint.deleteStatus = 500;

			HttpSession both = new Whep.Session(rtc, failingToClose(peerError),
					endpoint.uri().resolve("/live/session/1"), null, SESSION_TIMEOUT);
			Throwable error = assertCloseFails(both, "500");

			assertEquals(Arrays.asList(peerError), Arrays.asList(error.getSuppressed()));
			assertDeleteReported("500");
		}
	}

	@Test
	void redirectNotFollowed() throws Exception {
		// The deletion follows no redirect; WHIP and WHEP do not ask for that.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			endpoint.deleteStatus = 307;

			Whip.Session session = await(Whip.to(endpoint.uri()).bearerToken("secret").track(video).publish(rtc));

			assertCloseFails(session, "307");
			assertDeleteReported("307");
			assertFalse(endpoint.requests.stream().anyMatch(request -> request.path.equals(endpoint.redirect)),
					() -> "Requests: " + endpoint.requests);
		}
	}

	@Test
	void sessionKeepsItsToken() throws Exception {
		// The builder is changed while the offer is out. The session must
		// delete its resource with the token it created it with.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			CountDownLatch answer = endpoint.holdAnswers();
			Whip.Builder whip = Whip.to(endpoint.uri()).bearerToken("first").track(video);
			CompletableFuture<Whip.Session> session = whip.publish(rtc);

			endpoint.awaitRequest("POST /live/endpoint", TIMEOUT_SECONDS);
			whip.bearerToken("second");
			answer.countDown();
			await(await(session).closeAsync());

			assertEquals("Bearer first", endpoint.requests.get(0).authorization);
			assertEquals("DELETE /live/session/1", endpoint.requests.get(1).toString());
			assertEquals("Bearer first", endpoint.requests.get(1).authorization);
		}

		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			CountDownLatch answer = endpoint.holdAnswers();
			Whep.Builder whep = Whep.from(endpoint.uri()).bearerToken("first");
			CompletableFuture<Whep.Session> session = whep.play(rtc);

			endpoint.awaitRequest("POST /live/endpoint", TIMEOUT_SECONDS);
			whep.bearerToken("second");
			answer.countDown();
			await(await(session).closeAsync());

			assertEquals("DELETE /live/session/1", endpoint.requests.get(1).toString());
			assertEquals("Bearer first", endpoint.requests.get(1).authorization);
		}
	}

	@Test
	void resourceKeepsTls() {
		URI endpoint = URI.create("https://media.example/whep");

		assertNull(HttpSession.resourceOf(endpoint, null));
		assertEquals(URI.create("https://media.example/session/1"), HttpSession.resourceOf(endpoint, "/session/1"));
		// Another host is allowed, as WHIP and WHEP allow it.
		assertEquals(URI.create("https://edge.example/session/1"),
				HttpSession.resourceOf(endpoint, "https://edge.example/session/1"));

		assertThrows(WebRtcException.class,
				() -> HttpSession.resourceOf(endpoint, "http://media.example/session/1"));
		assertThrows(WebRtcException.class,
				() -> HttpSession.resourceOf(URI.create("http://media.example/whep"), "file:/etc/passwd"));

		// A local endpoint may name a local session over plain http, but no
		// other one.
		URI local = URI.create("http://127.0.0.1:8889/whep");
		assertEquals(URI.create("http://localhost:8889/session/1"),
				HttpSession.resourceOf(local, "http://localhost:8889/session/1"));
		assertThrows(WebRtcException.class,
				() -> HttpSession.resourceOf(local, "http://media.example/session/1"));
	}

	@Test
	void plainHttpOnlyOnLoopback() {
		// WHIP and WHEP ask for TLS, but a media server beside the
		// application may go without.
		for (String endpoint : Arrays.asList("https://media.example/whep", "http://localhost:8889/whep",
				"http://LOCALHOST/whep", "http://127.0.0.1:8889/whep", "http://127.5.0.1/whep",
				"http://[::1]:8889/whep")) {
			Whep.from(URI.create(endpoint));
			Whip.to(URI.create(endpoint));
		}

		// Names are not resolved: one could point here now and elsewhere
		// later.
		for (String endpoint : Arrays.asList("http://media.example/whep", "http://10.0.0.1/whep",
				"http://localhost.example/whep", "http://[::2]/whep", "ftp://localhost/whep")) {
			assertThrows(IllegalArgumentException.class, () -> Whep.from(URI.create(endpoint)), endpoint);
			assertThrows(IllegalArgumentException.class, () -> Whip.to(URI.create(endpoint)), endpoint);
		}
	}

	/**
	 * An endpoint that counters every offer with one of its own, from a peer
	 * that sends the video, and applies the answer a PATCH carries.
	 *
	 * @param contentType the Content-Type of the counter-offer.
	 * @param answers     gets the answers.
	 * @param tracks      what the endpoint sends; the video if none.
	 */
	private TestHttpEndpoint counteringEndpoint(String contentType, List<String> answers, LocalTrack... tracks)
			throws Exception {
		TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201);
		Peer[] server = new Peer[1];

		endpoint.counterOffers(() -> {
			server[0] = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());

			for (LocalTrack track : tracks.length > 0 ? tracks : new LocalTrack[] { video }) {
				server[0].addTrack(track);
			}

			try {
				return server[0].negotiator().createOffer(Gathering.COMPLETE)
						.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).sdp;
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}, contentType, answer -> {
			answers.add(answer);

			try {
				server[0].negotiator().applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER, answer))
						.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});

		return endpoint;
	}

	/**
	 * An endpoint whose side of the session sends the video.
	 */
	private TestHttpEndpoint sendingEndpoint() throws Exception {
		return new TestHttpEndpoint(offer -> {
			Peer server = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
			server.addTrack(video);
			return answer(server, offer);
		}, 201);
	}

	/**
	 * A runtime for the client's side of a session, apart from the one the
	 * endpoint's side runs on, so that its threads can be held or stopped.
	 */
	private WebRtc clientRuntime(UnaryOperator<WebRtc.Builder> options) {
		return options.apply(WebRtc.builder()
				.audio(AudioMode.HEADLESS)
				.errorHandler(errors::add))
				.build();
	}

	/**
	 * Waits until a task with the delay is scheduled on the timer, which an
	 * answer read on a thread of the HTTP client does.
	 */
	private static void awaitScheduled(ManualTimer timer, Duration delay) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (timer.scheduled(delay) == 0) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("Nothing was scheduled in " + delay + " within " + TIMEOUT_SECONDS + " s");
			}

			Thread.sleep(10);
		}
	}

	/**
	 * Starts a session and cancels it while the server holds the answer,
	 * which must cut off the request.
	 */
	private static void assertCancelAborts(WebRtc client,
			Function<URI, CompletableFuture<? extends HttpSession>> start) throws Exception {
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			endpoint.stallAnswers();
			CompletableFuture<? extends HttpSession> session = start.apply(endpoint.uri());

			endpoint.awaitRequest("POST /live/endpoint", TIMEOUT_SECONDS);
			PeerImpl peer = (PeerImpl) client.peers().get(0);

			assertTrue(session.cancel(true));
			// Closed by the cancellation itself, so it never connects.
			assertTrue(peer.isClosing(), () -> "Not closing: " + peer);

			endpoint.awaitHangUp(TIMEOUT_SECONDS);
			await(peer.whenClosed());

			assertTrue(session.isCancelled());
		}
	}

	/**
	 * Closes a runtime whose control tasks run only when the test runs them.
	 */
	static void closeStepping(WebRtc runtime, ManualExecutor control) throws Exception {
		CompletableFuture<Void> closed = runtime.closeAsync();

		control.runUntil(closed::isDone, TIMEOUT_SECONDS);
		await(closed);
	}

	/**
	 * An endpoint whose side of the session receives what is published.
	 */
	private TestHttpEndpoint receivingEndpoint() throws Exception {
		return new TestHttpEndpoint(offer -> answer(rtc.createPeer(PeerOptions.builder()
				.negotiation(Negotiation.MANUAL)
				.build()), offer), 201);
	}

	/**
	 * Checks that closing the session fails with the deletion the server
	 * answered with the given status.
	 *
	 * @return the failure.
	 */
	private static Throwable assertCloseFails(HttpSession session, String status) {
		ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session.closeAsync()));
		Throwable error = failed.getCause();

		assertInstanceOf(WebRtcException.class, error);
		assertTrue(error.getMessage().contains("Deleting the session"), error::getMessage);
		assertTrue(error.getCause().getMessage().contains(status), () -> error.getCause().getMessage());

		return error;
	}

	/**
	 * A peer whose close fails, as no peer of the runtime can be made to.
	 */
	private static Peer failingToClose(Throwable error) {
		return (Peer) Proxy.newProxyInstance(Peer.class.getClassLoader(), new Class<?>[] { Peer.class },
				(proxy, method, args) -> {
					if (method.getName().equals("closeAsync")) {
						return ClientFuture.failed(error);
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}

	/**
	 * Checks that the one error reported is the failed deletion, and takes it
	 * out of those that fail the test.
	 */
	private void assertDeleteReported(String status) {
		assertEquals(1, errors.size(), () -> "Errors: " + errors);
		assertTrue(errors.get(0).getCause().getMessage().contains(status), () -> "Errors: " + errors);
		errors.clear();
	}

	private static String answer(Peer peer, String offer) {
		try {
			return peer.negotiator()
					.answer(new RTCSessionDescription(RTCSdpType.OFFER, offer), Gathering.COMPLETE)
					.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).sdp;
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

}
