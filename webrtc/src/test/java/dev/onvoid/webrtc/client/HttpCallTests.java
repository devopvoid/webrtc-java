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

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.Test;

/**
 * The HTTP of WHIP and WHEP sessions alone, without WebRTC: bodies, aborts,
 * TLS, redirects and proxies.
 */
class HttpCallTests {

	private static final long TIMEOUT_SECONDS = 15;

	/** Longer than any test takes: a request times out only when a test wants it to. */
	private static final Duration TIMEOUT = Duration.ofSeconds(25);

	private static final String ANSWER = "v=0\r\n" + String.join("", Collections.nCopies(2000, "a=x\r\n"));

	/** Stands for the stream key or signature of a URL, which no failure may show. */
	static final String SECRET = "s3cr3t-t0k3n";


	@Test
	void framedAnswersRead() throws Exception {
		// Servers send the answer in chunks, or end it by closing the
		// connection, and in several pieces either way.
		HttpClient client = HttpCall.newClient(false);

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201)) {
			endpoint.chunkAnswers();
			assertEquals(ANSWER, readAnswer(client, endpoint.uri()));
		}
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201)) {
			endpoint.answerUntilClose();
			assertEquals(ANSWER, readAnswer(client, endpoint.uri()));
		}
	}

	@Test
	void problemDetailsDescribed() {
		// RFC 9457: the title and the detail say what went wrong.
		assertEquals("status 403: Forbidden: The stream key has expired", response(403, "application/problem+json",
				"{\"type\":\"https://example.com/probs/key\",\"title\":\"Forbidden\",\"status\":403,"
						+ "\"detail\":\"The stream key has expired\"}").describe());
		// The type when there is nothing else, unless it says no more than
		// the status.
		assertEquals("status 409: https://example.com/probs/busy", response(409,
				"application/problem+json; charset=utf-8", "{\"type\":\"https://example.com/probs/busy\"}").describe());
		assertEquals("status 500", response(500, "application/problem+json", "{\"type\":\"about:blank\"}").describe());
		// Problem details that cannot be read, and other bodies, as they are.
		assertEquals("status 400: {\"title\":", response(400, "application/problem+json", "{\"title\":").describe());
		assertEquals("status 404: not found", response(404, "text/plain", "not found").describe());
		assertEquals("status 503", response(503, null, "").describe());
		assertEquals("status 502: ".length() + HttpCall.MAX_ERROR_BODY,
				response(502, "text/html", "a".repeat(5000)).describe().length());
	}

	@Test
	void problemDetailsOfError() {
		// The status and the problem details, for a program to read.
		HttpStatusException typed = response(409, "application/problem+json",
				"{\"type\":\"https://example.com/probs/busy\",\"title\":\" Busy \"}").error("refused");

		assertEquals("refused", typed.getMessage());
		assertEquals(409, typed.status());
		assertEquals("https://example.com/probs/busy", typed.problemType());
		assertEquals("Busy", typed.title());
		assertNull(typed.detail());

		HttpStatusException plain = response(404, "text/plain", "not found").error("gone");

		assertEquals(404, plain.status());
		assertNull(plain.problemType());
		assertNull(plain.title());
		assertNull(plain.detail());
	}

	@Test
	void oversizedAnswerRefused() throws Exception {
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201)) {
			CompletableFuture<HttpCall.Response> response = post(HttpCall.newClient(false), endpoint.uri())
					.send("application/sdp", new byte[] { 'o' }, 1024, head -> { });

			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(response));
			assertTrue(failed.getCause().getMessage().contains("larger than 1024"), failed.getCause().getMessage());
		}
	}

	@Test
	void deadlineEndsSilentBody() throws Exception {
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "v=0 and the rest", 201)) {
			endpoint.stallBodies();
			CountDownLatch head = new CountDownLatch(1);
			HttpCall call = new HttpCall(HttpCall.newClient(false), endpoint.uri(), "POST", null,
					Duration.ofSeconds(2));
			CompletableFuture<HttpCall.Response> response = call.send("application/sdp", new byte[] { 'o' },
					1024, received -> head.countDown());
			try {
				assertTrue(head.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
				ExecutionException failed = assertThrows(ExecutionException.class, () -> await(response));
				assertInstanceOf(HttpTimeoutException.class, failed.getCause());
				endpoint.awaitHangUp(TIMEOUT_SECONDS);
			}
			finally {
				call.abort();
			}
		}
	}

	@Test
	void abortEndsRead() throws Exception {
		// The server sends the head and half the answer, then waits. An abort
		// ends the read of the rest at once, long before the timeout.
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "v=0 and the rest", 201)) {
			endpoint.stallBodies();

			CountDownLatch head = new CountDownLatch(1);
			HttpCall call = post(HttpCall.newClient(false), endpoint.uri());
			CompletableFuture<HttpCall.Response> response = call.send("application/sdp", new byte[] { 'o' }, 1024,
					received -> {
						assertEquals("/live/session/1", received.header("Location"));
						head.countDown();
					});

			assertTrue(head.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			call.abort();

			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(response));
			assertTrue(failed.getCause().getMessage().contains("aborted"), failed.getCause().getMessage());
			endpoint.awaitHangUp(TIMEOUT_SECONDS);
		}
	}

	@Test
	void tlsTrustsOnlyItsAuthorities() throws Exception {
		TestTls tls = TestTls.get();

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201, tls.server)) {
			assertEquals(ANSWER, readAnswer(HttpClient.newBuilder().sslContext(tls.client).build(), endpoint.uri()));

			// The default context knows nothing of the test's certificate.
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> readAnswer(HttpCall.newClient(false), endpoint.uri()));
			assertInstanceOf(SSLHandshakeException.class, failed.getCause(), failed.getCause()::toString);
		}
	}

	@Test
	void redirectKeepsTls() throws Exception {
		// The bearer token would go out in the clear.
		TestTls tls = TestTls.get();

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201, tls.server)) {
			endpoint.redirectAnswers(307, "http://127.0.0.1:1/live/endpoint");

			HttpCall call = post(HttpClient.newBuilder().sslContext(tls.client).build(), endpoint.uri())
					.followRedirects();
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(call.send("application/sdp", new byte[1], 1024, head -> { })));

			assertTrue(failed.getCause().getMessage().contains("without TLS"), failed.getCause().getMessage());
		}
	}

	@Test
	void redirectLeavesNoLoopbackInClear() throws Exception {
		// Plain http is for this machine only, wherever a redirect points.
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201)) {
			endpoint.redirectAnswers(307, "http://media.example/live/endpoint");

			HttpCall call = post(HttpCall.newClient(false), endpoint.uri()).followRedirects();
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(call.send("application/sdp", new byte[1], 1024, head -> { })));

			assertTrue(failed.getCause().getMessage().contains("without TLS"), failed.getCause().getMessage());
		}
	}

	@Test
	void urlsRedacted() {
		assertEquals("https://example.com", HttpCall.redacted(URI.create("https://example.com")));
		assertEquals("https://example.com:8443",
				HttpCall.redacted(URI.create("https://user:pass@example.com:8443/key/whip?token=x#y")));
		assertEquals("http://[::1]:8080", HttpCall.redacted(URI.create("http://[::1]:8080/key")));
		// A host that java.net.URI cannot read, which leaves only the authority.
		assertEquals("https://under_score.example:8443",
				HttpCall.redacted(URI.create("https://user:pass@under_score.example:8443/key")));
		assertEquals("mailto:", HttpCall.redacted(URI.create("mailto:key@example.com")));
		assertEquals("a URL without a scheme", HttpCall.redacted(URI.create("key/whip?token=x")));
	}

	@Test
	void invalidRequestHidesSecrets() {
		// The client's own messages quote the URL and the header field.
		HttpClient client = HttpCall.newClient(false);
		URI unsupported = URI.create("https://under_score.invalid/" + SECRET + "?key=" + SECRET);
		URI valid = URI.create("https://example.invalid/");

		for (HttpCall call : Arrays.asList(post(client, unsupported),
				new HttpCall(client, valid, "POST", "Bearer " + SECRET + "\r\nX: y", TIMEOUT))) {
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(call.send("application/sdp", new byte[1], 1024, head -> { })));

			assertTrue(failed.getCause().getMessage().contains("is invalid"), failed.getCause().getMessage());
			assertHidesSecret(failed);
		}
	}

	@Test
	void failedRedirectHidesSecrets() throws Exception {
		List<String> locations = Arrays.asList("/not valid?key=" + SECRET, "http://media.example/" + SECRET,
				"mailto:" + SECRET + "@example.com");

		for (String location : locations) {
			try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201)) {
				endpoint.redirectAnswers(307, location);

				HttpCall call = post(HttpCall.newClient(false), withSecret(endpoint.uri())).followRedirects();

				assertHidesSecret(assertThrows(ExecutionException.class,
						() -> await(call.send("application/sdp", new byte[1], 1024, head -> { }))));
			}
		}
	}

	@Test
	void abortHidesSecrets() throws Exception {
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "v=0 and the rest", 201)) {
			endpoint.stallBodies();

			CountDownLatch head = new CountDownLatch(1);
			HttpCall call = post(HttpCall.newClient(false), withSecret(endpoint.uri()));
			CompletableFuture<HttpCall.Response> response = call.send("application/sdp", new byte[] { 'o' }, 1024,
					received -> head.countDown());

			assertTrue(head.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
			call.abort();

			ExecutionException failed = assertThrows(ExecutionException.class, () -> await(response));

			assertTrue(failed.getCause().getMessage().contains("aborted"), failed.getCause().getMessage());
			assertHidesSecret(failed);
		}
	}

	@Test
	void timeoutHidesSecrets() throws Exception {
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "v=0 and the rest", 201)) {
			endpoint.stallBodies();

			HttpCall call = new HttpCall(HttpCall.newClient(false), withSecret(endpoint.uri()), "POST", null,
					Duration.ofMillis(200));
			ExecutionException failed = assertThrows(ExecutionException.class,
					() -> await(call.send("application/sdp", new byte[] { 'o' }, 1024, head -> { })));

			assertInstanceOf(HttpTimeoutException.class, failed.getCause());
			assertHidesSecret(failed);
		}
	}

	@Test
	void proxyForwardsRequest() throws Exception {
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201);
				TestHttpProxy proxy = new TestHttpProxy()) {
			HttpClient client = HttpClient.newBuilder().proxy(new Proxies(proxy.proxy())).build();

			assertEquals(ANSWER, readAnswer(client, endpoint.uri()));
			assertEquals("POST " + endpoint.uri(), proxy.requests.get(0).toString());
			assertEquals("POST /live/endpoint", endpoint.requests.get(0).toString());
		}
	}

	@Test
	void proxyAsksForCredentials() throws Exception {
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201);
				TestHttpProxy proxy = new TestHttpProxy()) {
			proxy.requiredAuthorization = basic("user", "secret");
			List<String> asked = new CopyOnWriteArrayList<>();

			HttpClient client = HttpClient.newBuilder()
					.proxy(new Proxies(proxy.proxy()))
					.authenticator(new Credentials(asked))
					.build();

			assertEquals(ANSWER, readAnswer(client, endpoint.uri()));
			assertEquals(Arrays.asList("test PROXY"), asked);
			assertNull(proxy.requests.get(0).authorization);
			assertEquals(basic("user", "secret"), proxy.requests.get(1).authorization);
			// Not passed on to the server.
			assertEquals(1, endpoint.requests.size());
		}
	}

	@Test
	void tunnelCarriesTls() throws Exception {
		TestTls tls = TestTls.get();

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201, tls.server);
				TestHttpProxy proxy = new TestHttpProxy()) {
			HttpClient client = HttpClient.newBuilder()
					.sslContext(tls.client)
					.proxy(new Proxies(proxy.proxy()))
					.build();

			assertEquals(ANSWER, readAnswer(client, endpoint.uri()));
			assertEquals("CONNECT 127.0.0.1:" + endpoint.uri().getPort(), proxy.requests.get(0).toString());
		}
	}

	@Test
	void tunnelRefusesBasicByDefault() throws Exception {
		// As jdk.http.auth.tunneling.disabledSchemes says by default: Basic
		// would hand the credentials to the proxy outside of TLS.
		TestTls tls = TestTls.get();

		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> ANSWER, 201, tls.server);
				TestHttpProxy proxy = new TestHttpProxy()) {
			proxy.requiredAuthorization = basic("user", "secret");
			List<String> asked = new CopyOnWriteArrayList<>();

			HttpClient client = HttpClient.newBuilder()
					.sslContext(tls.client)
					.proxy(new Proxies(proxy.proxy()))
					.authenticator(new Credentials(asked))
					.build();

			HttpCall.Response response = await(post(client, endpoint.uri())
					.send("application/sdp", new byte[] { 'o' }, 1024, head -> { }));

			// The proxy's refusal of the tunnel is the answer.
			assertEquals(407, response.status);
			assertTrue(asked.isEmpty(), () -> "Asked: " + asked);
			assertTrue(endpoint.requests.isEmpty(), () -> "Requests: " + endpoint.requests);
		}
	}

	@Test
	void overflowingRetryDateReturnsResponse() throws Exception {
		ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
		try (TestHttpEndpoint endpoint = new TestHttpEndpoint(offer -> "come back later", 201)) {
			endpoint.answerStatus = 503;
			endpoint.answerHeaders = "Retry-After: 31 Dec 999999999 23:59:59 GMT\r\n";
			HttpCall call = post(HttpCall.newClient(false), endpoint.uri()).retry(timer, Set.of(503));
			try {
				HttpCall.Response response = await(call.send("application/sdp", new byte[] { 'o' }, 1024,
						head -> { }));
				assertEquals(503, response.status);
				assertEquals("come back later", response.body);
				assertEquals(1, endpoint.requests.size());
			}
			finally {
				call.abort();
			}
		}
		finally {
			timer.shutdownNow();
		}
	}

	@Test
	void retryAfterRead() {
		long now = 1_000_000_000_000L;
		String inFive = DateTimeFormatter.RFC_1123_DATE_TIME.format(
				Instant.ofEpochMilli(now + 5000).atZone(ZoneOffset.UTC));
		String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(
				Instant.ofEpochMilli(now - 5000).atZone(ZoneOffset.UTC));

		assertEquals(3000, HttpCall.retryDelayMillis("3", now));
		assertEquals(0, HttpCall.retryDelayMillis(" 0 ", now));
		assertEquals(5000, HttpCall.retryDelayMillis(inFive, now));
		assertEquals(0, HttpCall.retryDelayMillis(past, now));
		assertEquals(Long.MAX_VALUE, HttpCall.retryDelayMillis("99999999999999999999", now));
		assertEquals(-1, HttpCall.retryDelayMillis("31 Dec 999999999 23:59:59 GMT", now));
		assertEquals(-1, HttpCall.retryDelayMillis("soon", now));
		assertEquals(-1, HttpCall.retryDelayMillis("", now));
		assertEquals(-1, HttpCall.retryDelayMillis(null, now));
	}

	/**
	 * @return the URL with {@link #SECRET} in each of its parts that may hold one.
	 */
	static URI withSecret(URI uri) {
		return URI.create(uri.getScheme() + "://user:" + SECRET + "@" + uri.getRawAuthority() + uri.getRawPath()
				+ "/" + SECRET + "?key=" + SECRET + "#" + SECRET);
	}

	/**
	 * Checks all that a log of the failure shows: the messages of its whole
	 * chain of causes, and what was suppressed along the way.
	 */
	static void assertHidesSecret(Throwable failure) {
		StringWriter log = new StringWriter();

		failure.printStackTrace(new PrintWriter(log, true));

		assertFalse(log.toString().contains(SECRET), log::toString);
	}

	private static HttpCall post(HttpClient client, URI uri) {
		return new HttpCall(client, uri, "POST", null, TIMEOUT);
	}

	/**
	 * Posts an offer as a session does, and reads the answer.
	 */
	private static String readAnswer(HttpClient client, URI uri) throws Exception {
		HttpCall.Response response = await(post(client, uri)
				.send("application/sdp", new byte[] { 'o' }, 1 << 20, head -> { }));

		assertEquals(201, response.status);
		assertEquals("/live/session/1", response.header("Location"));

		return response.body;
	}

	private static <T> T await(CompletableFuture<T> future) throws Exception {
		return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	private static HttpCall.Response response(int status, String type, String body) {
		Map<String, List<String>> fields = type == null ? Map.of() : Map.of("Content-Type", List.of(type));

		return new HttpCall.Response(URI.create("https://example.com/whip"), status,
				HttpHeaders.of(fields, (name, value) -> true), body);
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password)
				.getBytes(StandardCharsets.ISO_8859_1));
	}



	/** Selects the given proxies, in order. */
	private static final class Proxies extends ProxySelector {

		private final List<Proxy> proxies;


		Proxies(Proxy... proxies) {
			this.proxies = Arrays.asList(proxies);
		}

		@Override
		public List<Proxy> select(URI uri) {
			return proxies;
		}

		@Override
		public void connectFailed(URI uri, SocketAddress address, IOException error) {
		}
	}



	/** Gives user and secret, and records who asked for them. */
	private static final class Credentials extends Authenticator {

		private final List<String> asked;


		Credentials(List<String> asked) {
			this.asked = asked;
		}

		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			asked.add(getRequestingPrompt() + " " + getRequestorType());
			return new PasswordAuthentication("user", "secret".toCharArray());
		}
	}
}
