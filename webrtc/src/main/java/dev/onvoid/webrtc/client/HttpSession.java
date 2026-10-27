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
 * and closes the peer, independently of each other.
 */
abstract class HttpSession implements AutoCloseable {

	/** The largest answer read; an SDP answer is a few kilobytes. */
	private static final int MAX_BODY = 1 << 20;

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
	 * server holds for it, and closes the peer. Both start at once, so that a
	 * slow server does not keep the peer open. The deletion takes at most the
	 * session's timeout. A deletion that fails also goes to the runtime's
	 * error handler, for those who close without waiting.
	 *
	 * @return a future that completes once both are done, or fails with a
	 * {@link WebRtcException} when the resource could not be deleted, or with
	 * why the peer did not close. A failure of both carries the peer's as a
	 * suppressed exception.
	 */
	public CompletableFuture<Void> closeAsync() {
		if (closing.compareAndSet(false, true)) {
			CompletableFuture<Throwable> peerClosed = peer.closeAsync().handle((ignored, error) -> error);
			CompletableFuture<WebRtcException> deleted = discard(rtc, resource, bearerToken, timeout);

			deleted.thenAcceptBoth(peerClosed, (deleteError, peerError) -> {
				Throwable error = PeerImpl.unwrap(peerError);

				if (deleteError != null) {
					if (error != null) {
						deleteError.addSuppressed(error);
					}
					error = deleteError;
				}

				PeerImpl.complete(closed, null, error);
			});
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
	 * server named is deleted. An offer still out is cut off then, so that
	 * a server that does not answer holds no thread; what it creates for an
	 * offer it had yet to answer is left for it to time out.
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
		// Made now, so that it ends with the session's deadline at the latest,
		// however long the offer takes.
		HttpCall offerRequest = new HttpCall(rtc.httpClient(bearerToken != null), endpoint, "POST", bearerToken,
				timeout).followRedirects();

		// Either way the session ends, once: connected, failed or timed out.
		Consumer<Throwable> fail = error -> {
			if (!finished.compareAndSet(false, true)) {
				return;
			}

			// Ends an offer still out, whose answer would only meet a closed
			// peer.
			offerRequest.abort();
			// Reported before the resource is deleted, which may take as long
			// as the timeout again.
			peer.close();
			result.completeExceptionally(PeerImpl.asWebRtcException(error,
					"The session with " + endpoint + " could not be set up"));

			discard(rtc, resource.getAndSet(null), bearerToken, timeout);
		};

		ScheduledFuture<?> deadline = rtc.timer.schedule(() -> fail.accept(new WebRtcException(
				"The session with " + endpoint + " was not connected within " + timeout)),
				Timeouts.toMillis(timeout), TimeUnit.MILLISECONDS);

		abandoned.thenRun(() -> {
			deadline.cancel(false);
			fail.accept(new CancellationException("The session with " + endpoint + " was abandoned"));
		});

		peer.negotiator().createOffer(Gathering.COMPLETE)
				.thenCompose(offer -> post(offerRequest, endpoint, offer.sdp, resource)
						.whenComplete((answer, error) -> {
							if (finished.get()) {
								// The session ended while the offer was out,
								// after the server named what it created, which
								// then has no one else to delete it.
								discard(rtc, resource.getAndSet(null), bearerToken, timeout);
							}
						}))
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
	 * answer fails. The request follows the redirects WHIP and WHEP allow, and
	 * the resource is resolved against the endpoint that answered.
	 */
	private static CompletableFuture<String> post(HttpCall request, URI endpoint, String sdp,
			AtomicReference<URI> resource) {
		return request.send("application/sdp", sdp.getBytes(StandardCharsets.UTF_8), MAX_BODY, head -> {
			if (head.status == 201 || head.status == 200) {
				resource.set(resourceOf(head.uri, head.header("Location")));
			}
		}).handle((response, error) -> {
			if (error instanceof WebRtcException) {
				throw (WebRtcException) error;
			}
			if (error != null) {
				throw new WebRtcException("Sending the offer to " + endpoint + " failed: " + error.getMessage(),
						error);
			}
			if (response.status != 201 && response.status != 200) {
				throw new WebRtcException("The endpoint " + endpoint + " answered the offer with status "
						+ response.status + ": " + response.body);
			}

			return response.body;
		});
	}

	/**
	 * Makes the session of a peer that connected.
	 *
	 * @param <S> the session type.
	 */
	interface Factory<S extends HttpSession> {

		S create(WebRtc rtc, Peer peer, URI resource, String bearerToken, Duration timeout);
	}

	/**
	 * Deletes a resource that nothing else will, and reports rather than
	 * throws if that fails. The deletion takes at most the timeout from now on.
	 * A body of a 2xx answer is not read.
	 *
	 * @return a future with the failure reported, or null.
	 */
	private static CompletableFuture<WebRtcException> discard(WebRtc rtc, URI resource, String bearerToken,
			Duration timeout) {
		if (resource == null) {
			return CompletableFuture.completedFuture(null);
		}

		HttpCall request;

		try {
			request = new HttpCall(rtc.httpClient(bearerToken != null), resource, "DELETE", bearerToken, timeout);
		}
		catch (RejectedExecutionException e) {
			return CompletableFuture.completedFuture(leftOnServer(rtc, resource));
		}

		CompletableFuture<WebRtcException> deleted = request.send(null, null, 0, head -> { })
				.handle((response, error) -> {
					Throwable failure = error;

					if (failure == null && response.status / 100 != 2) {
						failure = new WebRtcException("The endpoint answered the deletion of " + resource
								+ " with status " + response.status + ": " + response.body);
					}
					if (failure == null) {
						return null;
					}

					WebRtcException reported = new WebRtcException("Deleting the session " + resource + " failed",
							failure);
					rtc.reportError(reported);
					return reported;
				});

		try {
			// The request's own timeout bounds the head of the answer; this ends
			// the body of an error that does not come.
			ScheduledFuture<?> deadline = rtc.timer.schedule(request::abort, Timeouts.toMillis(timeout),
					TimeUnit.MILLISECONDS);
			deleted.whenComplete((error, ignored) -> deadline.cancel(false));
		}
		catch (RejectedExecutionException e) {
			// The runtime is closing, and the request's own timeout bounds it.
		}

		return deleted;
	}

	/**
	 * @return the failure reported, or null if there was no resource.
	 */
	private static WebRtcException leftOnServer(WebRtc rtc, URI resource) {
		if (resource == null) {
			return null;
		}

		WebRtcException error = new WebRtcException("The session " + resource
				+ " was left on the server, since the runtime is closed");
		rtc.reportError(error);
		return error;
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

	static URI checkEndpoint(URI endpoint) {
		requireNonNull(endpoint, "endpoint");

		String scheme = endpoint.getScheme();

		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			throw new IllegalArgumentException("The endpoint must be an http or https URL, got " + endpoint);
		}

		return endpoint;
	}
}
