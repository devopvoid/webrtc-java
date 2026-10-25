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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.internal.Timeouts;

/**
 * A WHIP or WHEP session: a peer whose offer and answer went over HTTP, and the
 * resource the server created for it. Closing the session deletes the resource
 * and closes the peer.
 */
abstract class HttpSession implements AutoCloseable {

	/** The largest answer read; an SDP answer is a few kilobytes. */
	private static final int MAX_BODY = 1 << 20;

	/** How much of an error response goes into a message. */
	private static final int MAX_ERROR_BODY = 1024;

	private final Peer peer;

	private final URI resource;

	private final String bearerToken;

	private final Duration timeout;

	private final WebRtc rtc;

	private final AtomicBoolean closing = new AtomicBoolean();

	private final CompletableFuture<Void> closed = new CompletableFuture<>();


	HttpSession(WebRtc rtc, Peer peer, URI resource, String bearerToken, Duration timeout) {
		this.rtc = rtc;
		this.peer = peer;
		this.resource = resource;
		this.bearerToken = bearerToken;
		this.timeout = timeout;
	}

	/**
	 * @return the peer of the session, which negotiates by hand.
	 */
	public Peer peer() {
		return peer;
	}

	/**
	 * @return the resource the server created for the session, or null if it
	 * named none.
	 */
	public URI resource() {
		return resource;
	}

	/**
	 * Deletes the session's resource on the server, which frees what the
	 * server holds for it, and closes the peer.
	 *
	 * @return a future that completes once both are done.
	 */
	public CompletableFuture<Void> closeAsync() {
		if (closing.compareAndSet(false, true)) {
			CompletableFuture<Void> deleted;

			try {
				deleted = CompletableFuture.runAsync(() -> discard(rtc, resource, bearerToken, timeout),
						rtc.controlPool);
			}
			catch (RejectedExecutionException e) {
				leftOnServer(rtc, resource);
				deleted = CompletableFuture.completedFuture(null);
			}

			deleted.thenCompose(ignored -> peer.closeAsync())
					.whenComplete((ignored, error) -> closed.complete(null));
		}

		return ClientFuture.copyOf(closed);
	}

	/**
	 * Ends the session, as {@link #closeAsync()} does, without waiting.
	 */
	@Override
	public void close() {
		closeAsync();
	}

	/**
	 * Negotiates the peer over HTTP and hands the session out once the peer is
	 * connected. A caller that stops waiting, by cancelling the future or
	 * completing it itself, ends the negotiation, and a session made too late
	 * for the future is closed: either way the peer is closed and what the
	 * server created is deleted.
	 *
	 * @param factory makes the session of the connected peer.
	 *
	 * @return the future handed to the application.
	 */
	static <S extends HttpSession> CompletableFuture<S> start(WebRtc rtc, PeerImpl peer, URI endpoint,
			String bearerToken, Duration timeout, Factory<S> factory) {
		CompletableFuture<Void> abandoned = new CompletableFuture<>();
		CompletableFuture<S> session = peer.toPublic(negotiate(rtc, peer, endpoint, bearerToken, timeout, abandoned)
				.thenApply(resource -> factory.create(rtc, peer, resource, bearerToken, timeout)),
				HttpSession::close);

		// Once the session was handed out, this finds the negotiation finished.
		session.whenComplete((value, error) -> abandoned.complete(null));

		return session;
	}

	/**
	 * Negotiates a manual peer over HTTP: creates the offer with all candidates,
	 * posts it, applies the answer and waits until the peer is connected.
	 *
	 * @param abandoned completes when no one waits for the session anymore,
	 *                  which then ends as if it had failed.
	 *
	 * @return a future with the resource the server named, or null.
	 */
	private static CompletableFuture<URI> negotiate(WebRtc rtc, Peer peer, URI endpoint, String bearerToken,
			Duration timeout, CompletableFuture<?> abandoned) {
		CompletableFuture<URI> result = new CompletableFuture<>();
		// The resource the server created. Whoever takes it out owns it: the
		// session that connected, or whoever deletes it.
		AtomicReference<URI> resource = new AtomicReference<>();
		AtomicBoolean finished = new AtomicBoolean();

		// Either way the session ends, once: connected, failed or timed out.
		Consumer<Throwable> fail = error -> {
			if (!finished.compareAndSet(false, true)) {
				return;
			}

			// Reported before the resource is deleted, which may take as long
			// as the timeout again.
			peer.close();
			result.completeExceptionally(PeerImpl.asWebRtcException(error,
					"The session with " + endpoint + " could not be set up"));

			URI created = resource.getAndSet(null);

			if (created != null) {
				try {
					CompletableFuture.runAsync(() -> discard(rtc, created, bearerToken, timeout), rtc.controlPool);
				}
				catch (RejectedExecutionException e) {
					leftOnServer(rtc, created);
				}
			}
		};

		ScheduledFuture<?> deadline = rtc.timer.schedule(() -> fail.accept(new WebRtcException(
				"The session with " + endpoint + " was not connected within " + timeout)),
				Timeouts.toMillis(timeout), TimeUnit.MILLISECONDS);

		abandoned.thenRun(() -> {
			deadline.cancel(false);
			fail.accept(new CancellationException("The session with " + endpoint + " was abandoned"));
		});

		peer.negotiator().createOffer(Gathering.COMPLETE)
				.thenApplyAsync(offer -> {
					try {
						return post(endpoint, offer.sdp, bearerToken, timeout, resource);
					}
					finally {
						if (finished.get()) {
							// The session ended while the offer was out, so what
							// the server created meanwhile has no one else to
							// delete it. The answer then meets a closed peer.
							discard(rtc, resource.getAndSet(null), bearerToken, timeout);
						}
					}
				}, rtc.controlPool)
				.thenCompose(answer -> peer.negotiator()
						.applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER, answer)))
				.thenCompose(applied -> peer.whenConnected())
				.whenComplete((connected, error) -> {
					deadline.cancel(false);

					if (error != null) {
						fail.accept(error);
					}
					else if (finished.compareAndSet(false, true)) {
						result.complete(resource.getAndSet(null));
					}
				});

		return result;
	}

	/**
	 * Posts the offer and returns the answer. The resource the server names is
	 * set as soon as it is known, so that it is deleted even if reading the
	 * answer fails.
	 */
	private static String post(URI endpoint, String sdp, String bearerToken, Duration timeout,
			AtomicReference<URI> resource) {
		HttpURLConnection connection = null;

		try {
			connection = open(endpoint, "POST", bearerToken, timeout);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/sdp");
			connection.setRequestProperty("Accept", "application/sdp");

			byte[] body = sdp.getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(body.length);

			try (OutputStream out = connection.getOutputStream()) {
				out.write(body);
			}

			int status = connection.getResponseCode();

			if (status != HttpURLConnection.HTTP_CREATED && status != HttpURLConnection.HTTP_OK) {
				throw new WebRtcException("The endpoint " + endpoint + " answered the offer with status " + status
						+ ": " + read(connection.getErrorStream(), MAX_ERROR_BODY, true));
			}

			resource.set(resourceOf(endpoint, connection.getHeaderField("Location")));

			return read(connection.getInputStream(), MAX_BODY, false);
		}
		catch (IOException e) {
			throw new WebRtcException("Sending the offer to " + endpoint + " failed: " + e.getMessage(), e);
		}
		finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * Makes the session of a peer that connected.
	 *
	 * @param <S> the session type.
	 */
	interface Factory<S extends HttpSession> {

		S create(WebRtc rtc, Peer peer, URI resource, String bearerToken, Duration timeout);
	}

	private static void delete(URI resource, String bearerToken, Duration timeout) throws IOException {
		HttpURLConnection connection = open(resource, "DELETE", bearerToken, timeout);

		try {
			int status = connection.getResponseCode();

			if (status / 100 != 2) {
				throw new WebRtcException("The endpoint answered the deletion of " + resource + " with status "
						+ status + ": " + read(connection.getErrorStream(), MAX_ERROR_BODY, true));
			}
		}
		finally {
			connection.disconnect();
		}
	}

	/**
	 * Deletes a resource that nothing else will, and reports rather than
	 * throws if that fails.
	 */
	private static void discard(WebRtc rtc, URI resource, String bearerToken, Duration timeout) {
		if (resource == null) {
			return;
		}

		try {
			delete(resource, bearerToken, timeout);
		}
		catch (Exception e) {
			rtc.reportError(new WebRtcException("Deleting the session " + resource + " failed", e));
		}
	}

	private static void leftOnServer(WebRtc rtc, URI resource) {
		if (resource != null) {
			rtc.reportError(new WebRtcException("The session " + resource
					+ " was left on the server, since the runtime is closed"));
		}
	}

	/**
	 * Resolves the resource the server named for a session. It may be on
	 * another host, as WHIP and WHEP allow, but the bearer token goes along to
	 * delete it, so an https endpoint cannot name a plain http resource.
	 */
	static URI resourceOf(URI endpoint, String location) {
		if (location == null) {
			return null;
		}

		URI resource;

		try {
			resource = endpoint.resolve(location.trim());
		}
		catch (IllegalArgumentException e) {
			throw new WebRtcException("The endpoint " + endpoint + " named an invalid resource: " + location, e);
		}

		String scheme = resource.getScheme();

		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new WebRtcException("The endpoint " + endpoint + " named a resource that is not http: " + resource);
		}
		if ("https".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(scheme)) {
			throw new WebRtcException("The endpoint " + endpoint + " named a resource without TLS: " + resource);
		}

		return resource;
	}

	private static HttpURLConnection open(URI uri, String method, String bearerToken, Duration timeout)
			throws IOException {
		HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
		connection.setRequestMethod(method);
		// Never 0, which the connection takes as no timeout at all.
		int millis = (int) Math.min(Integer.MAX_VALUE, Timeouts.toMillis(timeout));
		connection.setConnectTimeout(millis);
		connection.setReadTimeout(millis);
		// A redirect would take the bearer token to wherever it points, so it
		// fails the request instead.
		connection.setInstanceFollowRedirects(false);

		if (bearerToken != null) {
			connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
		}

		return connection;
	}

	/**
	 * Reads a response body of at most the given size. A larger one fails, or
	 * is cut off when only a message is made of it.
	 */
	private static String read(InputStream in, int limit, boolean truncate) throws IOException {
		if (in == null) {
			return "";
		}

		try (InputStream stream = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int count;

			while ((count = stream.read(buffer)) != -1) {
				if (out.size() + count > limit) {
					if (!truncate) {
						throw new IOException("The response is larger than " + limit + " bytes");
					}

					out.write(buffer, 0, limit - out.size());
					break;
				}

				out.write(buffer, 0, count);
			}

			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	static URI checkEndpoint(URI endpoint) {
		requireNonNull(endpoint, "endpoint");

		String scheme = endpoint.getScheme();

		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new IllegalArgumentException("The endpoint must be an http or https URL, got " + endpoint);
		}

		return endpoint;
	}
}
