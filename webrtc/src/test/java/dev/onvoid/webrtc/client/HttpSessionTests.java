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
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
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
	void lateAnswerDeleted() throws Exception {
		// The session times out while the offer is out. The server created a
		// resource for it all the same, which must not be left there.
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			CountDownLatch answer = endpoint.holdAnswers();
			CompletableFuture<Whep.Session> session = Whep.from(endpoint.uri()).timeout(SESSION_TIMEOUT).play(client);

			endpoint.awaitRequest("POST /live/endpoint", TIMEOUT_SECONDS);
			assertEquals(1, timer.fire(SESSION_TIMEOUT));

			// Failed before the answer arrives, which then meets a closed peer.
			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session));
			assertTrue(failed.getCause().getMessage().contains("within"), failed.getCause().getMessage());

			answer.countDown();
			endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);

			client.close();
			assertEquals(1, endpoint.count("DELETE /live/session/1"));
		}
		finally {
			client.close();
		}
	}

	@Test
	void shortTimeoutBoundsRequest() throws Exception {
		// Under a millisecond, which the connection takes as no timeout at all
		// if it is rounded down. The session's own deadline is on a timer that
		// does not fire, so only the request's timeout can end the wait.
		Duration timeout = Duration.ofNanos(1);
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			CountDownLatch answer = endpoint.holdAnswers();

			try {
				ExecutionException failed = assertThrows(ExecutionException.class,
						() -> await(Whep.from(endpoint.uri()).timeout(timeout).play(client)));

				assertInstanceOf(SocketTimeoutException.class, failed.getCause().getCause(),
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
	void cancelledStartDeletes() throws Exception {
		// The caller cancels while the offer is out. The peer must not go on to
		// connect, and what the server creates must not be left there.
		WebRtc client = clientRuntime(builder -> builder);

		try {
			CustomVideo track = client.media().customVideo("video");

			assertCancelDeletes(client, uri -> Whip.to(uri).track(track).publish(client));
			assertCancelDeletes(client, uri -> Whep.from(uri).play(client));
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

			// The client's side up to the answer: the peer is created, the
			// offer is created and posted, and the answer read. The request
			// runs on this thread, within the task that sends it.
			control.runUntil(() -> endpoint.answered() > 0, TIMEOUT_SECONDS);
			assertEquals(1, timer.fire(SESSION_TIMEOUT));
			control.runAll();

			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(await(playing)));
			assertTrue(failed.getCause().getMessage().contains("within"), failed.getCause().getMessage());
			// Deleted on this thread, by the last tasks run.
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
		// Following it would take the bearer token to wherever it points.
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
	 * Starts a session, cancels it while the answer is held and releases the
	 * answer, whose resource must then be deleted.
	 */
	private static void assertCancelDeletes(WebRtc client,
			Function<URI, CompletableFuture<? extends HttpSession>> start) throws Exception {
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			CountDownLatch answer = endpoint.holdAnswers();
			CompletableFuture<? extends HttpSession> session = start.apply(endpoint.uri());

			endpoint.awaitRequest("POST /live/endpoint", TIMEOUT_SECONDS);
			PeerImpl peer = (PeerImpl) client.peers().get(0);

			assertTrue(session.cancel(true));
			// Closed by the cancellation itself, so it never connects.
			assertTrue(peer.isClosing(), () -> "Not closing: " + peer);

			answer.countDown();
			endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);
			await(peer.whenClosed());

			assertTrue(session.isCancelled());
			assertEquals(1, endpoint.count("DELETE /live/session/1"));
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
