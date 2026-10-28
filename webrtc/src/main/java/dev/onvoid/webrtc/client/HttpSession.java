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
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.onvoid.webrtc.RTCIceServer;
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

	/** The status of a counter-offer, whose body is an offer to answer. */
	private static final Set<Integer> COUNTER_OFFER = Set.of(406);

	/** How long a counter-offer can be answered if it does not say. */
	private static final long COUNTER_OFFER_VALIDITY_MILLIS = 30_000;

	/** The "valid-until" parameter of a counter-offer's Content-Type. */
	private static final Pattern VALID_UNTIL = Pattern.compile(";\\s*valid-until\\s*=\\s*\"?([^\";]+)\"?",
			Pattern.CASE_INSENSITIVE);

	private final Peer peer;

	private final URI resource;

	private final String bearerToken;

	private final Duration timeout;

	private final WebRtc rtc;

	/** The ICE of the session, or null for a session made without. */
	private final HttpIce ice;

	private final AtomicBoolean closing = new AtomicBoolean();

	private final CompletableFuture<Void> closed = new CompletableFuture<>();


	HttpSession(WebRtc rtc, Peer peer, URI resource, String bearerToken, Duration timeout, HttpIce ice) {
		this.rtc = rtc;
		this.peer = peer;
		this.resource = resource;
		this.bearerToken = bearerToken;
		this.timeout = timeout;
		this.ice = ice;

		if (ice != null) {
			ice.watch();
		}
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
	 * @return the ICE of the session, or null.
	 */
	HttpIce ice() {
		return ice;
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
			if (ice != null) {
				ice.close();
			}

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
	 * Restarts ICE, as after the network changed: the peer gathers new
	 * candidates, and only the ICE information goes to the session, by PATCH,
	 * as WHIP and WHEP do; the media go on as negotiated. The session restarts
	 * ICE by itself too, when the connection fails or stays disconnected for
	 * two seconds, up to three times in a row without success.
	 *
	 * @return a future that completes once the new ICE information of both
	 * sides is applied, or fails, among others when the session takes no ICE
	 * restarts or answered the offer with one of its own. The ICE session
	 * before then goes on.
	 */
	public CompletableFuture<Void> restartIce() {
		CompletableFuture<Void> restarted = ice != null
				? ice.restart()
				: CompletableFuture.failedFuture(new WebRtcException("The session has no ICE to restart"));

		return ClientFuture.copyOf(restarted);
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
	 * server named is deleted. A request still out is cut off then, so that
	 * a server that does not answer holds no connection; what it creates for
	 * an offer it had yet to answer is left for it to time out.
	 *
	 * @param protocol what WHIP or WHEP asks beyond the offer and its answer.
	 * @param factory  makes the session of the connected peer.
	 *
	 * @return the future handed to the application.
	 */
	static <S extends HttpSession> CompletableFuture<S> start(WebRtc rtc, PeerImpl peer, URI endpoint,
			String bearerToken, Duration timeout, Protocol protocol, Factory<S> factory) {
		CompletableFuture<Void> abandoned = new CompletableFuture<>();
		HttpIce ice = new HttpIce(rtc, rtc.httpClient(bearerToken != null), peer, bearerToken, timeout);
		CompletableFuture<S> session = peer.toPublic(negotiate(rtc, peer, endpoint, bearerToken, timeout, protocol,
				ice, abandoned).thenApply(resource -> factory.create(rtc, peer, resource, bearerToken, timeout, ice)),
				HttpSession::close);

		// Once the session was handed out, this finds the negotiation finished.
		session.whenComplete((value, error) -> abandoned.complete(null));

		return session;
	}

	/**
	 * Negotiates a manual peer over HTTP: creates the offer and posts it at
	 * once, without candidates, as RFC 9725 recommends with Trickle ICE. Only
	 * then is the offer applied, with the STUN and TURN servers the endpoint
	 * named, and the candidates it gathers go to the session by PATCH. The
	 * answer is applied, and the future completes once the peer is connected.
	 * An endpoint that counters the offer, as WHEP allows, gets the answer to
	 * its own offer instead, with all candidates.
	 *
	 * @param ice       the ICE of the session once the offer is answered.
	 * @param abandoned completes when no one waits for the session anymore,
	 *                  which then ends as if it had failed.
	 *
	 * @return a future with the resource the server named, or null.
	 */
	private static CompletableFuture<URI> negotiate(WebRtc rtc, PeerImpl peer, URI endpoint, String bearerToken,
			Duration timeout, Protocol protocol, HttpIce ice, CompletableFuture<?> abandoned) {
		CompletableFuture<URI> result = new CompletableFuture<>();
		long started = System.nanoTime();
		// The resource the server created. Whoever takes it out owns it: the
		// session that connected, or whoever deletes it.
		AtomicReference<URI> resource = new AtomicReference<>();
		AtomicBoolean finished = new AtomicBoolean();
		HttpClient client = rtc.httpClient(bearerToken != null);
		// Made now, so that it ends with the session's deadline at the latest,
		// however long the offer takes.
		HttpCall offerRequest = new HttpCall(client, endpoint, "POST", bearerToken, timeout)
				.followRedirects()
				.retry(rtc.timer, protocol.retried)
				.readBodies(protocol.counterOffers ? COUNTER_OFFER : Collections.emptySet());
		// The request under way, which the end of the session cuts off.
		AtomicReference<HttpCall> request = new AtomicReference<>(offerRequest);

		// Either way the session ends, once: connected, failed or timed out.
		Consumer<Throwable> fail = error -> {
			if (!finished.compareAndSet(false, true)) {
				return;
			}

			// Ends a request still out, whose answer would only meet a closed
			// peer.
			request.get().abort();
			ice.close();
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

		peer.offer()
				.thenCompose(offer -> post(offerRequest, endpoint, offer.sdp, protocol, resource)
						.whenComplete((reply, error) -> {
							if (finished.get()) {
								// The session ended while the offer was out,
								// after the server named what it created, which
								// then has no one else to delete it.
								discard(rtc, resource.getAndSet(null), bearerToken, timeout);
							}
						})
						.thenCompose(reply -> peer.useIceServers(reply.iceServers).handle((used, error) -> {
							if (error != null) {
								// Gathered without them, as if the endpoint had
								// named none.
								rtc.reportError(PeerImpl.unwrap(error));
							}

							return reply;
						}))
						.thenCompose(reply -> {
							if (!reply.counterOffer) {
								// Before the offer is applied, which starts the
								// gathering of the candidates that go there.
								ice.attach(resource.get(), reply.etag, offer.sdp);

								return peer.applyLocal(offer).thenCompose(applied -> peer.negotiator()
										.applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER, reply.sdp)));
							}

							// The rest of the session's time, which the answer has.
							Duration left = timeout.minusNanos(System.nanoTime() - started);

							return answerCounterOffer(rtc, client, peer, reply, bearerToken,
									left.isNegative() || left.isZero() ? Duration.ofNanos(1) : left, resource,
									request, finished);
						}))
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
	 * Posts the offer and returns what the endpoint answered with. The
	 * resource the server names with an answer is set as soon as it is known,
	 * so that it is deleted even if reading the answer fails. The request
	 * follows the redirects WHIP and WHEP allow, and the resource is resolved
	 * against the endpoint that answered.
	 */
	private static CompletableFuture<Reply> post(HttpCall request, URI endpoint, String sdp, Protocol protocol,
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
			if (response.status == 201 || response.status == 200) {
				return new Reply(response.body, false, null, 0, response);
			}
			if (protocol.counterOffers && isCounterOffer(response)) {
				URI session = resourceOf(response.uri, response.header("Location"));

				if (session == null) {
					throw new WebRtcException("The endpoint " + endpoint
							+ " countered the offer without naming the session");
				}

				return new Reply(response.body, true, session,
						counterOfferExpiry(response.header("Content-Type"), System.currentTimeMillis()), response);
			}

			String body = response.body;

			if (body.length() > HttpCall.MAX_ERROR_BODY) {
				body = body.substring(0, HttpCall.MAX_ERROR_BODY);
			}

			throw new WebRtcException("The endpoint " + endpoint + " answered the offer with status "
					+ response.status + ": " + body);
		});
	}

	/**
	 * Answers the endpoint's own offer, which replaces the peer's, and sends
	 * the answer to the session the endpoint named. The session is the
	 * peer's to delete from the moment the answer goes out, since the server
	 * creates it only then.
	 *
	 * @param timeout what is left of the session's timeout.
	 */
	private static CompletableFuture<Void> answerCounterOffer(WebRtc rtc, HttpClient client, PeerImpl peer,
			Reply reply, String bearerToken, Duration timeout, AtomicReference<URI> resource,
			AtomicReference<HttpCall> request, AtomicBoolean finished) {
		return peer.answerInstead(new RTCSessionDescription(RTCSdpType.OFFER, reply.sdp), Gathering.COMPLETE)
				.thenCompose(answer -> {
					if (System.currentTimeMillis() > reply.validUntil) {
						throw new WebRtcException("The counter-offer for " + reply.session
								+ " expired before it was answered");
					}

					HttpCall answerRequest = new HttpCall(client, reply.session, "PATCH", bearerToken, timeout);
					request.set(answerRequest);

					if (finished.get()) {
						// Ended meanwhile, by what may have aborted the request
						// before.
						answerRequest.abort();
					}
					else {
						resource.set(reply.session);
					}

					return answerRequest.send("application/sdp", answer.sdp.getBytes(StandardCharsets.UTF_8), 0,
							head -> { }).handle((response, error) -> {
								if (error != null) {
									throw new WebRtcException("Sending the answer to " + reply.session + " failed: "
											+ error.getMessage(), error);
								}
								if (response.status / 100 != 2) {
									throw new WebRtcException("The endpoint answered the answer for "
											+ reply.session + " with status " + response.status + ": "
											+ response.body);
								}

								return null;
							});
				});
	}

	/**
	 * Tells a counter-offer from any other 406 answer, which WHEP asks to be
	 * taken as an error: one has an SDP offer as its body.
	 */
	private static boolean isCounterOffer(HttpCall.Response response) {
		String type = response.header("Content-Type");

		if (response.status != 406 || type == null) {
			return false;
		}

		String mediaType = type.split(";", 2)[0].trim();

		return mediaType.equalsIgnoreCase("application/sdp") && response.body.startsWith("v=0")
				&& response.body.contains("\nm=");
	}

	/**
	 * Reads until when a counter-offer can be answered: the "valid-until"
	 * parameter of its Content-Type, an HTTP date, or else 30 seconds after
	 * it came, as WHEP says.
	 *
	 * @param contentType the Content-Type of the counter-offer.
	 * @param nowMillis   the time it came, as {@link System#currentTimeMillis()}.
	 *
	 * @return the time, as {@link System#currentTimeMillis()}.
	 */
	static long counterOfferExpiry(String contentType, long nowMillis) {
		Matcher validUntil = VALID_UNTIL.matcher(contentType != null ? contentType : "");

		if (validUntil.find()) {
			try {
				return ZonedDateTime.parse(validUntil.group(1).trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
						.toInstant().toEpochMilli();
			}
			catch (DateTimeParseException e) {
				// Not an HTTP date; as if there were none.
			}
		}

		return nowMillis + COUNTER_OFFER_VALIDITY_MILLIS;
	}

	/**
	 * What WHIP or WHEP asks of a session beyond the offer and its answer.
	 */
	static final class Protocol {

		/**
		 * The statuses of an answer to the offer, together with a Retry-After
		 * header field, after which the offer is sent again, as long as the
		 * wait fits within the timeout.
		 */
		final Set<Integer> retried;

		/** Whether the endpoint may counter the offer with one of its own. */
		final boolean counterOffers;


		Protocol(Set<Integer> retried, boolean counterOffers) {
			this.retried = retried;
			this.counterOffers = counterOffers;
		}
	}



	/**
	 * What the endpoint answered the offer with: an answer, or an offer of its
	 * own.
	 */
	private static final class Reply {

		final String sdp;

		/** Whether the SDP is the endpoint's offer, which replaces the peer's. */
		final boolean counterOffer;

		/** Where the session is once a counter-offer is answered. */
		final URI session;

		/** Until when a counter-offer can be answered. */
		final long validUntil;

		/** The entity-tag of the ICE session, or null. */
		final String etag;

		/** The STUN and TURN servers the endpoint named. */
		final List<RTCIceServer> iceServers;


		Reply(String sdp, boolean counterOffer, URI session, long validUntil, HttpCall.Response response) {
			this.sdp = sdp;
			this.counterOffer = counterOffer;
			this.session = session;
			this.validUntil = validUntil;
			this.etag = response.header("ETag");
			this.iceServers = HttpIce.iceServers(response.headers.allValues("Link"));
		}
	}



	/**
	 * Makes the session of a peer that connected.
	 *
	 * @param <S> the session type.
	 */
	interface Factory<S extends HttpSession> {

		S create(WebRtc rtc, Peer peer, URI resource, String bearerToken, Duration timeout, HttpIce ice);
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
		if (!"https".equalsIgnoreCase(scheme) && !HttpCall.isLoopback(resource.getHost())) {
			throw new WebRtcException("The endpoint " + endpoint + " named a resource without TLS: " + resource);
		}

		return resource;
	}

	static URI checkEndpoint(URI endpoint) {
		requireNonNull(endpoint, "endpoint");

		String scheme = endpoint.getScheme();

		boolean local = "http".equalsIgnoreCase(scheme) && HttpCall.isLoopback(endpoint.getHost());

		if (!"https".equalsIgnoreCase(scheme) && !local) {
			throw new IllegalArgumentException("The endpoint must be an https URL, or an http one on this machine, "
					+ "such as http://localhost, got " + endpoint);
		}

		return endpoint;
	}
}
