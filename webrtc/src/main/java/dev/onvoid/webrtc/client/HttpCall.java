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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

/**
 * One HTTP request of a WHIP or WHEP session, with a deadline for all of it.
 * <p>
 * The request takes no thread while it waits. Its timeout counts from the
 * moment it is made and bounds it as a whole, redirects included: the head of
 * the response by the client's own request timeout, set to what is left, and
 * an independent deadline through the body, including when no more data arrives.
 * {@link #abort()} ends it from any thread: a body being read at once, and on
 * Java 16 and later a request still waiting for its head too; before that,
 * such a request ends with its timeout.
 * <p>
 * Redirects are followed only when asked for, and only those with status 307
 * and 308, which keep the method and body. Likewise, a request is only sent
 * again when asked for, after the wait a server names in Retry-After.
 */
final class HttpCall {

	/** The most redirects a request follows. */
	static final int MAX_REDIRECTS = 5;

	/** How much of an error response is read, for a message. */
	static final int MAX_ERROR_BODY = 1024;

	/**
	 * The shortest wait before a request is sent again, so that a server that
	 * says to come back at once is not asked many times a second.
	 */
	static final long MIN_RETRY_MILLIS = 1000;

	private final HttpClient client;

	private final String method;

	private final String authorization;

	private final Duration timeout;

	/** When the request was made, as {@link System#nanoTime()}. */
	private final long start;

	/** The timeout in nanoseconds, or {@link Long#MAX_VALUE} if it is longer. */
	private final long limit;

	private final CompletableFuture<Response> result = new CompletableFuture<>();

	private final URI uri;

	private boolean followRedirects;

	private ScheduledExecutorService retryTimer;

	private Set<Integer> retryStatuses = Collections.emptySet();

	/** Statuses other than 2xx whose body is read as that of a 2xx one. */
	private Set<Integer> answerStatuses = Collections.emptySet();

	/** Header fields the request carries beyond those it sets itself. */
	private final Map<String, String> headers = new LinkedHashMap<>();

	/** The exchange under way, which an abort cancels. */
	private CompletableFuture<?> exchange;

	/** The wait before the request is sent again, which an abort ends. */
	private Future<?> retry;

	/** The body being read, which an abort ends. */
	private Body body;

	private boolean aborted;


	/**
	 * @param authorization the value of the Authorization header, such as
	 *                      {@code Bearer <token>}, or null for none.
	 * @param timeout       how long the request may take, from now on, until
	 *                      its response has been read; redirects included.
	 */
	HttpCall(HttpClient client, URI uri, String method, String authorization, Duration timeout) {
		this.client = client;
		this.uri = uri;
		this.method = method;
		this.authorization = authorization;
		this.timeout = timeout;
		this.start = System.nanoTime();

		long nanos;

		try {
			nanos = timeout.toNanos();
		}
		catch (ArithmeticException e) {
			nanos = Long.MAX_VALUE;
		}

		this.limit = nanos;
	}

	/**
	 * Makes a client for these requests. It goes about the network as {@code
	 * HttpURLConnection} does where the client can: through the proxies of
	 * the default {@link ProxySelector}, with the default {@link SSLContext},
	 * and asking the default {@link CookieHandler} of the moment. Redirects
	 * are left to the requests.
	 *
	 * @param authenticates whether to answer a proxy or server that asks for
	 *                      Basic credentials with those of the default {@link
	 *                      Authenticator} of the moment. A client that does
	 *                      drops the Authorization header of its requests, so
	 *                      it cannot send the credentials of a session.
	 *
	 * @return the client.
	 */
	static HttpClient newClient(boolean authenticates) {
		HttpClient.Builder builder = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.cookieHandler(new DefaultCookieHandler());

		if (authenticates) {
			builder.authenticator(new DefaultAuthenticator());
		}

		return builder.build();
	}

	/**
	 * Sends the request again when the server answers with one of the given
	 * statuses and a Retry-After header field, after the time it names, at
	 * least {@link #MIN_RETRY_MILLIS}, and as often as that fits within the
	 * timeout. Without the field, or once the wait would not fit, the answer
	 * is the response.
	 *
	 * @param timer    the timer the waits are on.
	 * @param statuses the statuses to send the request again for.
	 *
	 * @return this request.
	 */
	HttpCall retry(ScheduledExecutorService timer, Set<Integer> statuses) {
		this.retryTimer = timer;
		this.retryStatuses = statuses;
		return this;
	}

	/**
	 * Adds a header field to the request, such as If-Match.
	 *
	 * @return this request.
	 */
	HttpCall header(String name, String value) {
		headers.put(name, value);
		return this;
	}

	/**
	 * Reads the body of a response with one of the given statuses as that of
	 * a 2xx one, up to the size {@link #send} is given, rather than only as
	 * much as a message takes.
	 *
	 * @param statuses the statuses whose bodies are read in full.
	 *
	 * @return this request.
	 */
	HttpCall readBodies(Set<Integer> statuses) {
		this.answerStatuses = statuses;
		return this;
	}

	/**
	 * Follows redirects with status 307 and 308, up to {@link #MAX_REDIRECTS}
	 * of them, with the credentials. One from https to plain http fails.
	 *
	 * @return this request.
	 */
	HttpCall followRedirects() {
		this.followRedirects = true;
		return this;
	}

	/**
	 * Sends the request. Of a response with a status other than 2xx, at most
	 * {@link #MAX_ERROR_BODY} bytes of the body are read, for a message.
	 *
	 * @param contentType the type of the body, or null if there is none.
	 * @param content     the body, or null.
	 * @param maxBody     the largest body of a 2xx response; a larger one
	 *                    fails the request. With 0 the body is not read.
	 * @param onHead      gets the response without its body as soon as its
	 *                    head has come, unless the request was aborted
	 *                    before. What it throws fails the request.
	 *
	 * @return a future with the response, or that fails with an {@code
	 * IOException}, an {@link HttpTimeoutException} once the timeout has
	 * passed.
	 */
	CompletableFuture<Response> send(String contentType, byte[] content, int maxBody, Consumer<Response> onHead) {
		// The client's timeout ends at the response head. Keep a deadline
		// through the body and retries, even when the server goes silent.
		// Completing this guard removes its timeout from the JDK's timer.
		CompletableFuture<Void> deadline = new CompletableFuture<>();
		deadline.orTimeout(Math.max(0, remainingNanos()), TimeUnit.NANOSECONDS)
				.exceptionally(error -> {
					abort();
					return null;
				});
		result.whenComplete((response, error) -> deadline.complete(null));

		try {
			exchange(uri, contentType, content, maxBody, onHead, 0);
		}
		catch (RuntimeException e) {
			result.completeExceptionally(e);
		}

		return result;
	}

	/**
	 * Ends the request from any thread, at whatever step it is, and fails it:
	 * as timed out once its timeout has passed, as aborted before.
	 */
	void abort() {
		IOException reason = remainingNanos() <= 0
				? timedOut()
				: new IOException("The request to " + uri + " was aborted");
		CompletableFuture<?> exchange;
		Body body;
		Future<?> retry;

		synchronized (this) {
			if (aborted) {
				return;
			}

			aborted = true;
			exchange = this.exchange;
			body = this.body;
			retry = this.retry;
		}

		result.completeExceptionally(reason);

		if (retry != null) {
			retry.cancel(false);
		}
		if (body != null) {
			body.stop(reason);
		}
		if (exchange != null) {
			// From Java 16 on, this ends one that waits for its head as well.
			exchange.cancel(true);
		}
	}

	private void exchange(URI target, String contentType, byte[] content, int maxBody, Consumer<Response> onHead,
			int redirects) {
		long left = remainingNanos();

		if (left <= 0) {
			result.completeExceptionally(timedOut());
			return;
		}

		HttpRequest request;

		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(Duration.ofNanos(left));

			if (authorization != null) {
				builder.header("Authorization", authorization);
			}

			headers.forEach(builder::header);
			if (content != null) {
				builder.header("Content-Type", contentType)
						.header("Accept", contentType)
						.method(method, HttpRequest.BodyPublishers.ofByteArray(content));
			}
			else {
				builder.method(method, HttpRequest.BodyPublishers.noBody());
			}

			request = builder.build();
		}
		catch (IllegalArgumentException e) {
			result.completeExceptionally(new IOException("The request to " + target + " is invalid: "
					+ e.getMessage(), e));
			return;
		}

		CompletableFuture<HttpResponse<String>> response;

		synchronized (this) {
			if (aborted) {
				return;
			}

			response = client.sendAsync(request, info -> head(info, target, maxBody, onHead));
			exchange = response;
		}

		response.whenComplete((value, error) -> {
			if (error != null) {
				result.completeExceptionally(unwrap(error));
				return;
			}

			String location = value.headers().firstValue("Location").orElse(null);

			if (!isRedirect(value.statusCode(), location)) {
				long wait = retryStatuses.contains(value.statusCode())
						? retryDelayMillis(value.headers().firstValue("Retry-After").orElse(null),
								System.currentTimeMillis())
						: -1;

				if (wait < 0 || !retryLater(Math.max(wait, MIN_RETRY_MILLIS),
						() -> exchange(target, contentType, content, maxBody, onHead, redirects))) {
					result.complete(new Response(target, value.statusCode(), value.headers(), value.body()));
				}
				return;
			}
			if (redirects == MAX_REDIRECTS) {
				result.completeExceptionally(new IOException("The request to " + uri
						+ " was redirected more than " + MAX_REDIRECTS + " times"));
				return;
			}

			try {
				exchange(redirectTarget(target, location), contentType, content, maxBody, onHead, redirects + 1);
			}
			catch (IOException e) {
				result.completeExceptionally(e);
			}
		}).exceptionally(error -> {
			// A failure while processing a response must reach the caller,
			// rather than only the discarded completion stage.
			result.completeExceptionally(unwrap(error));
			return null;
		});
	}

	/**
	 * Takes the head of a response, and picks how much of its body to read.
	 */
	private HttpResponse.BodySubscriber<String> head(HttpResponse.ResponseInfo info, URI target, int maxBody,
			Consumer<Response> onHead) {
		int status = info.statusCode();
		boolean redirect = isRedirect(status, info.headers().firstValue("Location").orElse(null));
		boolean success = status / 100 == 2 || answerStatuses.contains(status);
		// A redirect's body is not read, and neither is an error's beyond what
		// a message takes.
		Body body = new Body(redirect ? 0 : success ? maxBody : MAX_ERROR_BODY, !success || redirect);

		synchronized (this) {
			if (aborted) {
				body.stop(new IOException("The request to " + uri + " was aborted"));
				return body;
			}

			this.body = body;

			if (!redirect) {
				try {
					// Here, so that an abort either comes first and the head
					// is never handed over, or after it.
					onHead.accept(new Response(target, status, info.headers(), null));
				}
				catch (RuntimeException e) {
					body.stop(e);
				}
			}
		}

		return body;
	}

	/**
	 * Sends the request again after the wait, if that leaves time for it.
	 *
	 * @return false if the request is not sent again.
	 */
	private boolean retryLater(long waitMillis, Runnable send) {
		if (TimeUnit.MILLISECONDS.toNanos(waitMillis) >= remainingNanos()) {
			return false;
		}

		synchronized (this) {
			if (aborted) {
				// Failed already.
				return true;
			}

			try {
				retry = retryTimer.schedule(send, waitMillis, TimeUnit.MILLISECONDS);
			}
			catch (RejectedExecutionException e) {
				// The runtime is closing.
				return false;
			}

			exchange = null;
			body = null;
		}

		return true;
	}

	/**
	 * Reads a Retry-After header field, in seconds or as an HTTP date.
	 *
	 * @param value     the field, or null.
	 * @param nowMillis the time, as {@link System#currentTimeMillis()}.
	 *
	 * @return how long to wait in milliseconds, or -1 if the field says
	 * nothing that can be read.
	 */
	static long retryDelayMillis(String value, long nowMillis) {
		if (value == null) {
			return -1;
		}

		String field = value.trim();

		if (!field.isEmpty() && field.chars().allMatch(Character::isDigit)) {
			try {
				return Math.multiplyExact(Long.parseLong(field), 1000);
			}
			catch (NumberFormatException | ArithmeticException e) {
				return Long.MAX_VALUE;
			}
		}

		try {
			long date = ZonedDateTime.parse(field, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();

			return Math.max(0, Math.subtractExact(date, nowMillis));
		}
		catch (DateTimeException | ArithmeticException e) {
			return -1;
		}
	}

	private boolean isRedirect(int status, String location) {
		return followRedirects && (status == 307 || status == 308) && location != null;
	}

	private static URI redirectTarget(URI from, String location) throws IOException {
		URI target;

		try {
			target = from.resolve(location.trim());
		}
		catch (IllegalArgumentException e) {
			throw new IOException("The server redirected to an invalid URL: " + location);
		}

		String scheme = target.getScheme();

		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new IOException("The server redirected to a URL that is not http: " + target);
		}
		if ("https".equalsIgnoreCase(from.getScheme()) && !"https".equalsIgnoreCase(scheme)) {
			throw new IOException("The server redirected to a URL without TLS: " + target);
		}
		if (!"https".equalsIgnoreCase(scheme) && !isLoopback(target.getHost())) {
			throw new IOException("The server redirected to a URL without TLS: " + target);
		}

		return target;
	}

	/**
	 * Tells whether a host is this machine, where WHIP and WHEP may go over
	 * plain http, to a media server beside the application; anywhere else they
	 * ask for TLS (RFC 9725, 5). The machine is known by name only as {@code
	 * localhost}, and otherwise by an address written out, in 127.0.0.0/8 or
	 * {@code ::1}: a name that resolves to it now may resolve elsewhere later.
	 *
	 * @param host the host of a URL, an IPv6 address in brackets, or null.
	 *
	 * @return whether the host is this machine.
	 */
	static boolean isLoopback(String host) {
		if (host == null) {
			return false;
		}

		String name = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;

		if (name.equalsIgnoreCase("localhost")) {
			return true;
		}
		// Only an address written out, which is parsed without asking DNS.
		if (!name.matches("\\d{1,3}(\\.\\d{1,3}){3}") && !name.contains(":")) {
			return false;
		}

		try {
			return InetAddress.getByName(name).isLoopbackAddress();
		}
		catch (UnknownHostException e) {
			return false;
		}
	}

	private long remainingNanos() {
		return limit - (System.nanoTime() - start);
	}

	private HttpTimeoutException timedOut() {
		return new HttpTimeoutException("The request to " + uri + " did not complete within " + timeout);
	}

	private static Throwable unwrap(Throwable error) {
		return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
	}



	/**
	 * Asks the default authenticator of the moment, which a client would
	 * otherwise take once, when it is built.
	 */
	private static final class DefaultAuthenticator extends Authenticator {

		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			return Authenticator.requestPasswordAuthentication(getRequestingHost(), getRequestingSite(),
					getRequestingPort(), getRequestingProtocol(), getRequestingPrompt(), getRequestingScheme(),
					getRequestingURL(), getRequestorType());
		}
	}



	/**
	 * Asks the default cookie handler of the moment, if there is one.
	 */
	private static final class DefaultCookieHandler extends CookieHandler {

		@Override
		public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders)
				throws IOException {
			CookieHandler handler = CookieHandler.getDefault();

			return handler != null ? handler.get(uri, requestHeaders) : Collections.emptyMap();
		}

		@Override
		public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
			CookieHandler handler = CookieHandler.getDefault();

			if (handler != null) {
				handler.put(uri, responseHeaders);
			}
		}
	}



	/**
	 * A response: its status and headers, where it came from, and its body.
	 */
	static final class Response {

		/** The URL that answered, after the redirects followed. */
		final URI uri;

		final int status;

		final HttpHeaders headers;

		/** The body as read, or null where only the head is handed over. */
		final String body;


		Response(URI uri, int status, HttpHeaders headers, String body) {
			this.uri = uri;
			this.status = status;
			this.headers = headers;
			this.body = body;
		}

		/**
		 * @return the first value of the header, or null.
		 */
		String header(String name) {
			return headers.firstValue(name).orElse(null);
		}

		/**
		 * Describes the response for a message: its status, and what its body
		 * says. Of problem details (RFC 9457), which WHIP and WHEP endpoints
		 * may send with an error, those are the title and the detail, or the
		 * type if there are neither; any other body is given as it is, up to
		 * {@link #MAX_ERROR_BODY} chars.
		 *
		 * @return the description, such as {@code status 403: Forbidden: The
		 * stream key has expired}.
		 */
		String describe() {
			String description = "status " + status;
			Map<?, ?> problem = problemDetails();

			if (problem != null) {
				List<String> parts = new ArrayList<>();
				String title = member(problem, "title");
				String detail = member(problem, "detail");
				String type = member(problem, "type");

				if (title != null) {
					parts.add(title);
				}
				if (detail != null) {
					parts.add(detail);
				}
				// RFC 9457, 4.2.1: about:blank says no more than the status.
				if (parts.isEmpty() && type != null && !"about:blank".equals(type)) {
					parts.add(type);
				}

				return parts.isEmpty() ? description : description + ": " + String.join(": ", parts);
			}
			if (body == null || body.isEmpty()) {
				return description;
			}

			return description + ": " + (body.length() > MAX_ERROR_BODY ? body.substring(0, MAX_ERROR_BODY) : body);
		}

		/**
		 * @param message what failed, which describes the response, as {@link
		 *                #describe()} does.
		 *
		 * @return an exception with the message, the status of the response
		 * and what its problem details say.
		 */
		HttpStatusException error(String message) {
			Map<?, ?> problem = problemDetails();

			if (problem == null) {
				return new HttpStatusException(message, status, null, null, null);
			}

			return new HttpStatusException(message, status, member(problem, "type"), member(problem, "title"),
					member(problem, "detail"));
		}

		/**
		 * @return the members of the problem details of the body (RFC 9457),
		 * or null if the body has none, or none that can be read.
		 */
		private Map<?, ?> problemDetails() {
			String type = header("Content-Type");

			if (body == null || type == null
					|| !type.split(";", 2)[0].trim().equalsIgnoreCase("application/problem+json")) {
				return null;
			}

			Object parsed;

			try {
				parsed = Json.parse(body);
			}
			catch (IllegalArgumentException e) {
				// Cut short at the size read, or not JSON at all.
				return null;
			}

			return parsed instanceof Map ? (Map<?, ?>) parsed : null;
		}

		/**
		 * @return the string member of the name, trimmed, or null if there is
		 * none, or one that is not a string or is blank.
		 */
		private static String member(Map<?, ?> members, String name) {
			Object value = members.get(name);

			return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null;
		}
	}



	/**
	 * Reads a body of at most the given size, and no longer than the timeout.
	 * A larger one fails, or is cut off when only a message is made of it.
	 */
	private final class Body implements HttpResponse.BodySubscriber<String> {

		private final CompletableFuture<String> text = new CompletableFuture<>();

		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		private final int max;

		private final boolean truncate;

		private Flow.Subscription subscription;

		private boolean done;


		Body(int max, boolean truncate) {
			this.max = max;
			this.truncate = truncate;
		}

		@Override
		public CompletionStage<String> getBody() {
			return text;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			synchronized (this) {
				if (done) {
					subscription.cancel();
					return;
				}

				this.subscription = subscription;
			}

			if (max == 0) {
				stop(null);
				return;
			}

			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			if (remainingNanos() <= 0) {
				// A body that trickles in.
				stop(timedOut());
				return;
			}

			for (ByteBuffer item : items) {
				byte[] bytes = new byte[item.remaining()];
				item.get(bytes);

				if (out.size() + bytes.length > max) {
					if (!truncate) {
						stop(new IOException("The response is larger than " + max + " bytes"));
						return;
					}

					out.write(bytes, 0, max - out.size());
					stop(null);
					return;
				}

				out.write(bytes, 0, bytes.length);
			}
		}

		@Override
		public void onError(Throwable error) {
			finish(error);
		}

		@Override
		public void onComplete() {
			finish(null);
		}

		/**
		 * Ends the body before it is complete, which closes the connection,
		 * with what came so far or with the error.
		 */
		void stop(Throwable error) {
			Flow.Subscription subscription;

			synchronized (this) {
				if (done) {
					return;
				}

				done = true;
				subscription = this.subscription;
			}

			if (subscription != null) {
				subscription.cancel();
			}

			finish(error);
		}

		private void finish(Throwable error) {
			if (error != null) {
				text.completeExceptionally(error);
			}
			else {
				text.complete(new String(out.toByteArray(), StandardCharsets.UTF_8));
			}
		}
	}
}
