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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
			await(session.closeAsync());

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
			await(session.closeAsync());

			assertDeleteReported("500");
		}
	}

	@Test
	void redirectNotFollowed() throws Exception {
		// Following it would take the bearer token to wherever it points.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			endpoint.deleteStatus = 307;

			Whip.Session session = await(Whip.to(endpoint.uri()).bearerToken("secret").track(video).publish(rtc));
			await(session.closeAsync());

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
