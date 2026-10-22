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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpSessionTests extends ClientTestBase {

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
	void lateAnswerDeleted() throws Exception {
		// The answer arrives after the session timed out. The server created
		// a resource for it all the same, which must not be left there.
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "", 201)) {
			endpoint.stallMillis = 700;

			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> Whep.from(endpoint.uri()).timeout(Duration.ofSeconds(1)).play(rtc)
							.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));

			assertTrue(failed.getCause().getMessage().contains("within"), failed.getCause().getMessage());
			awaitRequest(endpoint, "DELETE /live/session/1");
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
			awaitRequest(endpoint, "DELETE /live/session/1");
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

	private static void awaitRequest(TestHttpEndpoint endpoint, String request) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

		while (endpoint.requests.stream().noneMatch(r -> r.toString().equals(request))
				&& System.nanoTime() < deadline) {
			Thread.sleep(50);
		}

		assertTrue(endpoint.requests.stream().anyMatch(r -> r.toString().equals(request)),
				() -> "Requests: " + endpoint.requests);
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
