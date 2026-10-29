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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

import dev.onvoid.webrtc.RTCBundlePolicy;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpSender;
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

			assertEquals(1, endpoint.count("DELETE /live/session/1"));
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
			assertEquals(1, endpoint.count("DELETE /live/session/1"));
		}
	}

	@Test
	void completeOffersNeedNoPatch() throws Exception {
		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.patchStatus = 405;
			Whep.Session session = await(Whep.from(endpoint.uri()).gathering(Gathering.COMPLETE).play(rtc));
			assertTrue(endpoint.request("POST /live/endpoint").body.contains("a=candidate:"));
			CompletableFuture<Integer> width = new CompletableFuture<>();
			session.peer().onTrack(track -> track.asVideo().onFrame(frame -> width.complete(frame.buffer.getWidth())));
			assertEquals(160, (int) await(width));
			await(session.closeAsync());
			assertEquals(0, endpoint.count("PATCH /live/session/1"));
		}
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			endpoint.patchStatus = 405;
			Whip.Session session = await(Whip.to(endpoint.uri()).track(video)
					.gathering(Gathering.COMPLETE).publish(rtc));
			assertTrue(endpoint.request("POST /live/endpoint").body.contains("a=candidate:"));
			await(session.closeAsync());
			assertEquals(0, endpoint.count("PATCH /live/session/1"));
		}
	}

	@Test
	void completeOfferCanBeCountered() throws Exception {
		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", new ArrayList<>())) {
			Whep.Session session = await(Whep.from(endpoint.uri()).gathering(Gathering.COMPLETE).play(rtc));
			assertTrue(endpoint.request("POST /live/endpoint").body.contains("a=candidate:"));
			await(session.restartIce());
			await(session.closeAsync());
		}
	}

	@Test
	void turnCredentialsConfigured() throws Exception {
		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.answerHeaders = "Link: <stun:127.0.0.2:3478>; rel=\"ice-server\"\r\n";
			Whep.Session session = await(Whep.from(endpoint.uri())
					.turnServer("turn:127.0.0.1:3478", "user", "password").play(rtc));
			RTCIceServer server = await(session.peer().withNative(c -> c.getConfiguration().iceServers.get(0)));
			assertEquals(Arrays.asList("turn:127.0.0.1:3478"), server.urls);
			assertEquals("user", server.username);
			assertEquals("password", server.password);
			await(session.closeAsync());
		}
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Whip.Session session = await(Whip.to(endpoint.uri()).track(video)
					.turnServer("turn:127.0.0.1:3478", "user", "password")
					.iceServer("stun:127.0.0.2:3478", "stun:127.0.0.3:3478", "stun:127.0.0.4:3478")
					.publish(rtc));
			List<RTCIceServer> servers = await(session.peer().withNative(c -> c.getConfiguration().iceServers));
			assertEquals("password", servers.get(0).password);
			assertEquals(3, servers.get(1).urls.size());
			assertNull(servers.get(1).username);
			await(session.closeAsync());
		}
	}

	@Test
	void malformedSuccessDeleted() throws Exception {
		// A known resource must still be deleted when its answer is unusable.
		for (String type : Arrays.asList("text/plain", null)) {
			try (TestHttpEndpoint endpoint = sendingEndpoint()) {
				endpoint.answerContentType = type;
				ExecutionException failed = assertThrows(ExecutionException.class,
						() -> await(Whep.from(endpoint.uri()).play(rtc)));
				assertTrue(failed.getCause().getMessage().contains("Content-Type"));
				endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);
			}
		}
		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.answerStatus = 200;
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whep.from(endpoint.uri()).play(rtc)));
			assertTrue(failed.getCause().getMessage().contains("status 200"));
			endpoint.awaitRequest("DELETE /live/session/1", TIMEOUT_SECONDS);
		}
	}

	@Test
	void contentTypeParametersAccepted() throws Exception {
		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.answerContentType = "Application/SDP; charset=utf-8";
			Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));
			await(session.closeAsync());
		}
	}

	@Test
	void restartRequiresFragmentType() throws Exception {
		try (TestHttpEndpoint endpoint = restartingEndpoint(new ArrayList<>())) {
			Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));
			String previous = await(session.peer().withNative(c -> c.getLocalDescription().sdp));
			endpoint.iceRestartContentType = "text/plain";
			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session.restartIce()));
			assertTrue(failed.getCause().getMessage().contains("Content-Type"));
			assertEquals(previous, await(session.peer().withNative(c -> c.getLocalDescription().sdp)));
			await(session.closeAsync());
		}
	}

	@Test
	void sessionLocationRequired() throws Exception {
		for (String location : Arrays.asList(null, "")) {
			try (TestHttpEndpoint endpoint = sendingEndpoint()) {
				endpoint.location = location;
				ExecutionException failed = assertThrows(ExecutionException.class,
						() -> await(Whep.from(endpoint.uri()).play(rtc)));
				assertTrue(failed.getCause().getMessage().contains("Location"));
				assertEquals(0, endpoint.count("DELETE /live/session/1"));
			}
		}
	}

	@Test
	void currentPreconditionFailureReported() throws Exception {
		for (int status : new int[] { 412, 428 }) {
			CompletableFuture<Throwable> error = new CompletableFuture<>();
			WebRtc client = clientRuntime(builder -> builder.errorHandler(error::complete));
			try (TestHttpEndpoint endpoint = sendingEndpoint()) {
				endpoint.patchStatus = status;
				Whep.Session session = await(Whep.from(endpoint.uri()).play(client));
				assertTrue(await(error).getMessage().contains("status " + status));
				await(session.closeAsync());
			}
			finally {
				client.close();
			}
		}
	}

	@Test
	void stalePreconditionFailureIgnored() throws Exception {
		for (int status : new int[] { 412, 428, 405 }) {
			CountDownLatch release = new CountDownLatch(1);
			try (TestHttpEndpoint endpoint = restartingEndpoint(new ArrayList<>())) {
				// The first candidate PATCH fails only after a restart has
				// installed a new generation. Its failure must not stop new candidates.
				endpoint.patchStatus = status;
				endpoint.candidatePatch = request -> {
					if (!"\"restart-1\"".equals(request.ifMatch)) {
						try {
							assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
						}
						catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new IllegalStateException(e);
						}
					}
				};
				Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));
				endpoint.awaitRequest("PATCH /live/session/1", TIMEOUT_SECONDS);
				await(session.restartIce());
				endpoint.patchStatus = 204;
				release.countDown();
				endpoint.awaitRequest(r -> "\"restart-1\"".equals(r.ifMatch), "new candidates", TIMEOUT_SECONDS);
				await(session.closeAsync());
			}
			finally {
				release.countDown();
			}
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
	void secondTrackOfKindRefused() throws Exception {
		// RFC 9725, 4.4.2: one stream, with one track of each kind at most.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Whip.Builder whip = Whip.to(endpoint.uri()).track(video).track(rtc.media().customVideo("other"));

			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> whip.publish(rtc));

			assertTrue(refused.getMessage().contains("at most one video track"), refused.getMessage());
			assertTrue(rtc.peers().isEmpty(), () -> "Peers left: " + rtc.peers());
			assertTrue(endpoint.requests.isEmpty());
		}
	}

	@Test
	void offerBundledAndMuxed() throws Exception {
		// RFC 9725, 4.4.1: max-bundle, and rtcp-mux-only in each m-line.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Whip.Session session = await(Whip.to(endpoint.uri())
					.track(video)
					.track(rtc.media().customAudio("audio"))
					.publish(rtc));
			String offer = endpoint.request("POST /live/endpoint").body;
			RTCBundlePolicy policy = await(session.peer().withNative(connection ->
					connection.getConfiguration().bundlePolicy));

			assertEquals(RTCBundlePolicy.MAX_BUNDLE, policy);
			assertEquals(2, offer.split("\r\na=rtcp-mux-only\r\n", -1).length - 1, offer);
			assertEquals(2, offer.split("\r\nm=", -1).length - 1, offer);
			// RFC 9725, 4.2: a WHIP client should only send.
			assertEquals(2, offer.split("\r\na=sendonly\r\n", -1).length - 1, offer);
			assertFalse(offer.contains("a=sendrecv"), offer);

			await(session.closeAsync());
		}
	}

	@Test
	void namedCodecsOffered() throws Exception {
		// Only those named, in that order, by name or MIME type, any case.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Whip.Session session = await(Whip.to(endpoint.uri())
					.videoCodecs("vp9", "H264")
					.audioCodecs("audio/opus")
					.track(video)
					.track(rtc.media().customAudio("audio"))
					.publish(rtc));
			String offer = endpoint.request("POST /live/endpoint").body;

			assertEquals(List.of("VP9", "H264"), mediaCodecs(offer, "video"));
			assertEquals(List.of("opus"), mediaCodecs(offer, "audio"));
			// Retransmission goes along.
			assertTrue(offer.contains(" rtx/90000"), offer);

			// Each codec once, although WebRTC both sends and receives it.
			for (String line : offer.split("\r\n")) {
				if (line.startsWith("m=")) {
					String[] fields = line.split(" ");
					List<String> payloads = Arrays.asList(fields).subList(3, fields.length);
					assertEquals(new HashSet<>(payloads).size(), payloads.size(), line);
				}
			}

			await(session.closeAsync());
		}
	}

	@Test
	void unknownCodecLeavesNothing() throws Exception {
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			assertThrows(IllegalArgumentException.class, () -> Whip.to(endpoint.uri()).videoCodecs());
			assertThrows(IllegalArgumentException.class, () -> Whip.to(endpoint.uri()).audioCodecs(" "));

			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(Whip.to(endpoint.uri()).videoCodecs("NOPE").track(video).publish(rtc)));

			assertInstanceOf(ConfigurationException.class, failed.getCause());
			assertTrue(failed.getCause().getMessage().contains("NOPE"), failed.getCause().getMessage());
			assertTrue(endpoint.requests.isEmpty());
		}
	}

	@Test
	void videoOfferedWithoutTrack() throws Exception {
		// Some endpoints, such as Amazon IVS, refuse an offer without video,
		// even for audio alone.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Whip.Session session = await(Whip.to(endpoint.uri())
					.track(rtc.media().customAudio("audio"))
					.videoCodecs("H264")
					.alwaysOfferVideo()
					.publish(rtc));
			String offer = endpoint.request("POST /live/endpoint").body;
			List<String> streams = new ArrayList<>();

			for (String line : offer.split("\r\n")) {
				if (line.startsWith("a=msid:")) {
					streams.add(line.substring("a=msid:".length()).split(" ")[0]);
				}
			}

			assertEquals(List.of("H264"), mediaCodecs(offer, "video"));
			assertEquals(2, offer.split("\r\na=sendonly\r\n", -1).length - 1, offer);
			// RFC 9725, 4.4.2: all m-lines in one stream.
			assertEquals(2, streams.size(), offer);
			assertEquals(1, new HashSet<>(streams).size(), offer);

			await(session.closeAsync());
		}
	}

	@Test
	void simulcastOffered() throws Exception {
		// RFC 9725, 4.8: simulcast is negotiated in the offer, as RFC 8853 says.
		// WebRTC, which answers for the endpoint here, takes no simulcast, so
		// the answer takes it as a forwarding server would.
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> answer(rtc.createPeer(PeerOptions.builder()
				.negotiation(Negotiation.MANUAL).build()), offer).replace("\r\na=recvonly\r\n",
				"\r\na=recvonly\r\na=rid:f recv\r\na=rid:h recv\r\na=rid:q recv\r\na=simulcast:recv f;h;q\r\n"),
				201)) {
			Whip.Session session = await(Whip.to(endpoint.uri()).track(video).simulcast().publish(rtc));
			String offer = endpoint.request("POST /live/endpoint").body;
			String answer = await(session.peer().withNative(c -> c.getRemoteDescription().sdp));

			assertTrue(offer.contains("\r\na=simulcast:send f;h;q\r\n"), offer);
			assertTrue(offer.contains("\r\na=rid:f send\r\n"), offer);
			assertTrue(offer.contains("\r\na=rid:h send\r\n"), offer);
			assertTrue(offer.contains("\r\na=rid:q send\r\n"), offer);
			assertTrue(answer.contains("\r\na=simulcast:recv f;h;q\r\n"), answer);
			assertEquals(List.of("f/1.0", "h/2.0", "q/4.0"), layers(session.peer()));

			await(session.closeAsync());
		}
	}

	@Test
	void simulcastDeclinedSendsFirstLayer() throws Exception {
		// An endpoint that takes no simulcast leaves it out of its answer, as
		// WebRTC does, which answers for the endpoint here.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Whip.Session session = await(Whip.to(endpoint.uri()).track(video).simulcast().publish(rtc));
			String answer = await(session.peer().withNative(c -> c.getRemoteDescription().sdp));
			List<String> layers = layers(session.peer());

			assertFalse(answer.contains("a=simulcast"), answer);

			assertEquals(1, layers.size(), layers::toString);
			assertTrue(layers.get(0).endsWith("/1.0"), layers::toString);

			await(session.closeAsync());
		}
	}

	@Test
	void simulcastNeedsVideoTrack() throws Exception {
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			Encoding layer = Encoding.builder().build();

			assertThrows(IllegalArgumentException.class, () -> Whip.to(endpoint.uri()).simulcast(layer));
			assertThrows(IllegalArgumentException.class,
					() -> Whip.to(endpoint.uri()).simulcast(layer, layer, layer, layer));

			Whip.Builder whip = Whip.to(endpoint.uri()).track(rtc.media().customAudio("audio")).simulcast();

			assertThrows(IllegalStateException.class, () -> whip.publish(rtc));
			assertTrue(rtc.peers().isEmpty(), () -> "Peers left: " + rtc.peers());
			assertTrue(endpoint.requests.isEmpty());
		}
	}

	@Test
	void rtcpMuxOnlyMarked() {
		String sdp = String.join("\r\n",
				"v=0",
				"a=group:BUNDLE 0 1 2",
				"m=audio 9 UDP/TLS/RTP/SAVPF 111",
				"a=mid:0",
				"a=rtcp-mux",
				"m=video 9 UDP/TLS/RTP/SAVPF 96",
				"a=mid:1",
				"a=rtcp-mux",
				"a=rtcp-mux-only",
				"m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
				"a=mid:2",
				"");
		String marked = HttpSession.withRtcpMuxOnly(sdp);

		assertEquals(sdp.replace("a=mid:0\r\na=rtcp-mux\r\n", "a=mid:0\r\na=rtcp-mux\r\na=rtcp-mux-only\r\n"), marked);
		// Once is enough.
		assertEquals(marked, HttpSession.withRtcpMuxOnly(marked));
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
	void candidatesTrickled() throws Exception {
		// The offer goes out at once, without candidates. They follow by
		// PATCH, with the entity-tag of the ICE session, as RFC 9725 asks.
		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.answerHeaders = "ETag: \"ice-1\"\r\n";

			Whep.Session session = await(Whep.from(endpoint.uri()).bearerToken("secret").play(rtc));
			TestHttpEndpoint.Request offer = endpoint.request("POST /live/endpoint");
			TestHttpEndpoint.Request last = endpoint.awaitRequest(request -> request.method.equals("PATCH")
					&& request.body.contains("a=end-of-candidates"), "PATCH that ends the candidates", TIMEOUT_SECONDS);

			assertFalse(offer.body.contains("a=candidate:"), offer.body);
			assertEquals(HttpIce.FRAGMENT_TYPE, last.contentType);
			assertEquals("\"ice-1\"", last.ifMatch);
			assertEquals("Bearer secret", last.authorization);
			// The ICE information of the offer, then the candidates.
			assertTrue(last.body.startsWith(HttpIce.Fragment.of(offer.body).text), last.body);
			assertTrue(endpoint.requests.stream().anyMatch(request -> request.method.equals("PATCH")
					&& request.body.contains("a=candidate:")), () -> "Requests: " + endpoint.requests);

			await(session.closeAsync());
		}
	}

	@Test
	void iceServersFromLink() throws Exception {
		// The STUN and TURN servers the endpoint names, which the peer gathers
		// its candidates with.
		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.answerHeaders = "Link: <stun:127.0.0.1:3478>; rel=\"ice-server\"\r\n"
					+ "Link: <turn:127.0.0.1:3478?transport=udp>; rel=\"ice-server\"; username=\"user\"; "
					+ "credential=\"secret\"\r\n";

			Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));
			List<RTCIceServer> servers = await(session.peer().withNative(connection ->
					connection.getConfiguration().iceServers));

			assertEquals(2, servers.size(), () -> "Servers: " + servers);
			assertEquals(Arrays.asList("stun:127.0.0.1:3478"), servers.get(0).urls);
			assertEquals(Arrays.asList("turn:127.0.0.1:3478?transport=udp"), servers.get(1).urls);
			assertEquals("user", servers.get(1).username);
			assertEquals("secret", servers.get(1).password);

			await(session.closeAsync());
		}
	}

	@Test
	void ownIceServersWin() throws Exception {
		// Servers the application names override the endpoint's, as RFC 9725
		// allows.
		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			endpoint.answerHeaders = "Link: <stun:127.0.0.1:3478>; rel=\"ice-server\"\r\n";

			Whep.Session session = await(Whep.from(endpoint.uri()).iceServer("stun:127.0.0.2:3478").play(rtc));
			List<RTCIceServer> servers = await(session.peer().withNative(connection ->
					connection.getConfiguration().iceServers));

			assertEquals(1, servers.size(), () -> "Servers: " + servers);
			assertEquals(Arrays.asList("stun:127.0.0.2:3478"), servers.get(0).urls);

			await(session.closeAsync());
		}
	}

	@Test
	void iceRestarted() throws Exception {
		// Only the ICE information goes to the session, which answers with its
		// own; the media go on, and later candidates carry the new entity-tag.
		List<String> fragments = new CopyOnWriteArrayList<>();

		try (TestHttpEndpoint endpoint = restartingEndpoint(fragments)) {
			Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));
			String offered = HttpIce.Fragment.of(endpoint.request("POST /live/endpoint").body).ufrag;

			await(session.restartIce());

			TestHttpEndpoint.Request restart = endpoint.awaitRequest(request -> "*".equals(request.ifMatch),
					"ICE restart", TIMEOUT_SECONDS);
			String remote = await(session.peer().withNative(connection -> connection.getRemoteDescription().sdp));

			assertEquals(HttpIce.FRAGMENT_TYPE, restart.contentType);
			assertNotEquals(offered, HttpIce.Fragment.parse(restart.body).ufrag);
			assertTrue(remote.contains("a=ice-ufrag:" + HttpIce.Fragment.parse(fragments.get(0)).ufrag), remote);

			endpoint.awaitRequest(request -> "\"restart-1\"".equals(request.ifMatch),
					"candidates after the restart", TIMEOUT_SECONDS);

			CompletableFuture<Integer> width = new CompletableFuture<>();
			session.peer().onTrack(track -> track.asVideo().onFrame(frame -> width.complete(frame.buffer.getWidth())));
			assertEquals(160, (int) await(width));

			await(session.closeAsync());
		}
	}

	@Test
	void refusedIceRestartKeepsSession() throws Exception {
		// The endpoint takes no ICE restarts: the new offer is taken back, and
		// the ICE session before goes on.
		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));
			String offered = HttpIce.Fragment.of(endpoint.request("POST /live/endpoint").body).ufrag;

			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session.restartIce()));
			String local = await(session.peer().withNative(connection -> connection.getLocalDescription().sdp));

			assertTrue(failed.getCause().getMessage().contains("takes no ICE restarts"),
					failed.getCause().getMessage());
			assertEquals(offered, HttpIce.Fragment.of(local).ufrag);
			assertEquals(PeerState.CONNECTED, session.peer().state());

			await(session.closeAsync());
		}
	}

	@Test
	void failedConnectionRestartsIce() throws Exception {
		List<String> fragments = new CopyOnWriteArrayList<>();

		try (TestHttpEndpoint endpoint = restartingEndpoint(fragments)) {
			Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));

			session.ice().stateChanged(PeerState.FAILED);

			endpoint.awaitRequest(request -> "*".equals(request.ifMatch), "ICE restart", TIMEOUT_SECONDS);
			await(session.closeAsync());
		}
	}

	@Test
	void disconnectedWaitsBeforeRestart() throws Exception {
		// A connection that comes back by itself needs no restart.
		ManualTimer timer = new ManualTimer();
		WebRtc client = clientRuntime(builder -> builder.timer(timer));

		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			Whep.Session session = await(Whep.from(endpoint.uri()).timeout(SESSION_TIMEOUT).play(client));

			session.ice().stateChanged(PeerState.DISCONNECTED);
			assertEquals(1, timer.scheduled(HttpIce.DISCONNECTED_GRACE));

			session.ice().stateChanged(PeerState.CONNECTED);
			assertEquals(0, timer.scheduled(HttpIce.DISCONNECTED_GRACE));

			// Still connected when the wait is over: no restart either.
			session.ice().stateChanged(PeerState.DISCONNECTED);
			assertEquals(1, timer.fire(HttpIce.DISCONNECTED_GRACE));
			assertEquals(0, endpoint.requests.stream().filter(request -> "*".equals(request.ifMatch)).count());

			await(session.closeAsync());
		}
		finally {
			client.close();
		}
	}

	@Test
	void automaticRestartsLimited() throws Exception {
		// Restarts that fail are not made forever: three in a row at most.
		List<String> fragments = new CopyOnWriteArrayList<>();

		try (TestHttpEndpoint endpoint = restartingEndpoint(fragments)) {
			endpoint.iceRestartStatus = 500;

			Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));

			for (int i = 0; i <= HttpIce.MAX_AUTOMATIC_RESTARTS; i++) {
				session.ice().stateChanged(PeerState.FAILED);
			}

			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
			while (errors.size() < HttpIce.MAX_AUTOMATIC_RESTARTS && System.nanoTime() < deadline) {
				Thread.sleep(10);
			}

			assertEquals(HttpIce.MAX_AUTOMATIC_RESTARTS, errors.size(), () -> "Errors: " + errors);
			assertEquals(HttpIce.MAX_AUTOMATIC_RESTARTS,
					endpoint.requests.stream().filter(request -> "*".equals(request.ifMatch)).count());
			errors.clear();

			await(session.closeAsync());
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
			assertTrue(answers.get(0).contains("\r\na=rtcp-mux-only\r\n"), answers.get(0));
			assertFalse(answers.get(0).contains("a=sendrecv"), answers.get(0));
			// With its candidates, gathered anew after the player's offer was
			// taken back.
			assertTrue(answers.get(0).contains("a=candidate"), answers.get(0));

			await(session.closeAsync());
			assertEquals(1, endpoint.count("DELETE /live/session/1"));
		}
	}

	@Test
	void counterOfferRestarted() throws Exception {
		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", new ArrayList<>(),
				video, rtc.media().customAudio("audio"))) {
			Whep.Session session = await(Whep.from(endpoint.uri()).bearerToken("secret")
					.receive(MediaKind.VIDEO).play(rtc));
			String before = await(session.peer().withNative(c -> c.getLocalDescription().sdp));
			await(session.restartIce());
			String after = await(session.peer().withNative(c -> c.getLocalDescription().sdp));
			assertNotEquals(HttpIce.Fragment.of(before).ufrag, HttpIce.Fragment.of(after).ufrag);
			assertTrue(after.contains("\nm=audio 0 "), after);
			assertTrue(after.contains("a=recvonly"), after);
			assertFalse(after.contains("a=sendrecv"), after);
			assertEquals(RTCSdpType.OFFER, await(session.peer().withNative(c -> c.getRemoteDescription().sdpType)));
			TestHttpEndpoint.Request restart = endpoint.awaitRequest(r -> "*".equals(r.ifMatch),
					"ICE restart", TIMEOUT_SECONDS);
			assertEquals("Bearer secret", restart.authorization);
			assertEquals(HttpIce.FRAGMENT_TYPE, restart.contentType);
			assertEquals(HttpIce.Fragment.parse(restart.body).ufrag, HttpIce.Fragment.of(after).ufrag);
			endpoint.awaitRequest(r -> "\"restart-1\"".equals(r.ifMatch), "restart candidates", TIMEOUT_SECONDS);
			CompletableFuture<Integer> width = new CompletableFuture<>();
			session.peer().onTrack(track -> track.asVideo().onFrame(frame -> width.complete(frame.buffer.getWidth())));
			assertEquals(160, (int) await(width));
			// A second restart exercises the descriptions left by the first.
			await(session.restartIce());
			await(session.closeAsync());
		}
	}

	@Test
	void counterOfferRestartsAutomatically() throws Exception {
		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", new ArrayList<>())) {
			Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));
			session.ice().stateChanged(PeerState.FAILED);
			endpoint.awaitRequest(r -> "\"restart-1\"".equals(r.ifMatch), "restart candidates", TIMEOUT_SECONDS);
			await(session.closeAsync());
		}
	}

	@Test
	void counterOfferRestartRefused() throws Exception {
		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", new ArrayList<>())) {
			Whep.Session session = await(Whep.from(endpoint.uri()).play(rtc));
			String before = await(session.peer().withNative(c -> c.getLocalDescription().sdp));
			endpoint.iceRestartStatus = 405;
			assertThrows(ExecutionException.class, () -> await(session.restartIce()));
			assertEquals(before, await(session.peer().withNative(c -> c.getLocalDescription().sdp)));
			CompletableFuture<Integer> width = new CompletableFuture<>();
			session.peer().onTrack(track -> track.asVideo().onFrame(frame -> width.complete(frame.buffer.getWidth())));
			assertEquals(160, (int) await(width));
			endpoint.iceRestartStatus = 200;
			await(session.restartIce());
			await(session.closeAsync());
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
	void counterOfferAnsweredWithNamedCodecs() throws Exception {
		// The transceivers of the endpoint's offer are new, and take the
		// codecs named all the same.
		List<String> answers = new CopyOnWriteArrayList<>();

		try (TestHttpEndpoint endpoint = counteringEndpoint("application/sdp", answers)) {
			Whep.Session session = await(Whep.from(endpoint.uri()).videoCodecs("VP9").play(rtc));

			CompletableFuture<Integer> width = new CompletableFuture<>();
			session.peer().onTrack(track -> track.asVideo().onFrame(frame -> width.complete(frame.buffer.getWidth())));

			assertEquals(160, (int) await(width));
			assertEquals(List.of("VP9"), mediaCodecs(endpoint.request("POST /live/endpoint").body, "video"));
			assertEquals(List.of("VP9"), mediaCodecs(answers.get(0), "video"));

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
					Duration.ofSeconds(1), null);

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
		HttpSession session = new Whep.Session(rtc, failingToClose(peerError), null, null, SESSION_TIMEOUT, null);
		ExecutionException failed = assertThrows(ExecutionException.class, () -> await(session.closeAsync()));
		assertSame(peerError, failed.getCause());

		// Both fail: the deletion leads, and the peer's failure goes along.
		try (TestHttpEndpoint endpoint = receivingEndpoint()) {
			endpoint.deleteStatus = 500;

			HttpSession both = new Whep.Session(rtc, failingToClose(peerError),
					endpoint.uri().resolve("/live/session/1"), null, SESSION_TIMEOUT, null);
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
			assertEquals(1, endpoint.count("DELETE /live/session/1"));
			assertEquals("Bearer first", endpoint.request("DELETE /live/session/1").authorization);
		}

		try (TestHttpEndpoint endpoint = sendingEndpoint()) {
			CountDownLatch answer = endpoint.holdAnswers();
			Whep.Builder whep = Whep.from(endpoint.uri()).bearerToken("first");
			CompletableFuture<Whep.Session> session = whep.play(rtc);

			endpoint.awaitRequest("POST /live/endpoint", TIMEOUT_SECONDS);
			whep.bearerToken("second");
			answer.countDown();
			await(await(session).closeAsync());

			assertEquals(1, endpoint.count("DELETE /live/session/1"));
			assertEquals("Bearer first", endpoint.request("DELETE /live/session/1").authorization);
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

		endpoint.iceRestarts = fragment -> {
			try {
				String previous = await(server[0].withNative(c -> c.getRemoteDescription().sdp));
				await(server[0].withNative(connection -> {
					connection.restartIce();
					return null;
				}));
				String offer = await(server[0].negotiator().createOffer(Gathering.COMPLETE)).sdp;
				await(server[0].negotiator().applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER,
						HttpIce.Fragment.parse(fragment).applyTo(previous))));
				StringBuilder own = new StringBuilder(HttpIce.Fragment.of(offer).text);
				HttpIce.Fragment.parse(offer).candidates.forEach(candidate -> own.append(candidate).append("\r\n"));
				return own.append("a=end-of-candidates\r\n").toString();
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};

		return endpoint;
	}

	/**
	 * An endpoint whose side sends the video and takes ICE restarts, as a WHEP
	 * session that supports them: it restarts ICE with the player's new ICE
	 * information and answers with its own.
	 *
	 * @param fragments gets the fragments the endpoint answers with.
	 */
	private TestHttpEndpoint restartingEndpoint(List<String> fragments) throws Exception {
		Peer[] server = new Peer[1];
		TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> {
			server[0] = rtc.createPeer(PeerOptions.builder().negotiation(Negotiation.MANUAL).build());
			server[0].addTrack(video);
			return answer(server[0], offer);
		}, 201);

		endpoint.iceRestarts = fragment -> {
			try {
				String offer = server[0].withNative(connection -> connection.getRemoteDescription().sdp)
						.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				String answer = answer(server[0], HttpIce.Fragment.parse(fragment).applyTo(offer));
				StringBuilder own = new StringBuilder(HttpIce.Fragment.of(answer).text);

				HttpIce.Fragment.parse(answer).candidates.forEach(candidate -> own.append(candidate).append("\r\n"));
				own.append("a=end-of-candidates\r\n");
				fragments.add(own.toString());

				return own.toString();
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};

		return endpoint;
	}

	/**
	 * An endpoint whose side of the session sends the video.
	 */
	/**
	 * The media codecs of the m-line of a kind, in the order it lists them,
	 * each once, without retransmission, redundancy, FEC, comfort noise and
	 * DTMF.
	 */
	private static List<String> mediaCodecs(String sdp, String kind) {
		Set<String> companions = Set.of("rtx", "red", "ulpfec", "flexfec-03", "cn", "telephone-event");
		Map<String, String> names = new HashMap<>();
		List<String> payloads = new ArrayList<>();
		boolean section = false;

		for (String line : sdp.split("\r\n")) {
			if (line.startsWith("m=")) {
				section = line.startsWith("m=" + kind + " ");

				if (section) {
					String[] fields = line.split(" ");
					payloads.addAll(Arrays.asList(fields).subList(3, fields.length));
				}
			}
			else if (section && line.startsWith("a=rtpmap:")) {
				String[] fields = line.substring("a=rtpmap:".length()).split("[ /]");
				names.put(fields[0], fields[1]);
			}
		}

		List<String> codecs = new ArrayList<>();

		for (String payload : payloads) {
			String name = names.get(payload);

			if (name != null && !companions.contains(name.toLowerCase(Locale.ROOT)) && !codecs.contains(name)) {
				codecs.add(name);
			}
		}

		return codecs;
	}

	/**
	 * The rid and the factor of each encoding the only sender of the peer
	 * sends in.
	 */
	private List<String> layers(Peer peer) throws Exception {
		return await(peer.withNative(connection -> {
			List<String> layers = new ArrayList<>();
			RTCRtpSender[] senders = connection.getSenders();

			try {
				for (RTCRtpEncodingParameters encoding : senders[0].getParameters().encodings) {
					layers.add(encoding.rid + "/" + encoding.scaleResolutionDownBy);
				}
			}
			finally {
				// Each holds the track, which could not be released otherwise.
				for (RTCRtpSender sender : senders) {
					sender.dispose();
				}
			}

			return layers;
		}));
	}

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
