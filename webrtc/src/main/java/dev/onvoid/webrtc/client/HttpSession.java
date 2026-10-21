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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;

/**
 * A WHIP or WHEP session: a peer whose offer and answer went over HTTP, and the
 * resource the server created for it. Closing the session deletes the resource
 * and closes the peer.
 */
abstract class HttpSession implements AutoCloseable {

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
			CompletableFuture.runAsync(() -> {
				try {
					if (resource != null) {
						delete(resource, bearerToken, timeout);
					}
				}
				catch (Exception e) {
					rtc.reportError(new WebRtcException("Deleting the session " + resource + " failed", e));
				}
			}, rtc.controlPool)
					.thenCompose(ignored -> peer.closeAsync())
					.whenComplete((ignored, error) -> closed.complete(null));
		}

		return closed;
	}

	/**
	 * Ends the session, as {@link #closeAsync()} does, without waiting.
	 */
	@Override
	public void close() {
		closeAsync();
	}

	/**
	 * Negotiates a manual peer over HTTP: creates the offer with all candidates,
	 * posts it, applies the answer and waits until the peer is connected.
	 *
	 * @return a future with the resource the server named, or null.
	 */
	static CompletableFuture<URI> negotiate(WebRtc rtc, Peer peer, URI endpoint, String bearerToken,
			Duration timeout) {
		CompletableFuture<URI> result = new CompletableFuture<>();
		AtomicReference<URI> resource = new AtomicReference<>();
		AtomicBoolean finished = new AtomicBoolean();

		// Either way the session ends, once: connected, failed or timed out.
		Consumer<Throwable> fail = error -> {
			if (!finished.compareAndSet(false, true)) {
				return;
			}

			// Leaves nothing behind, on the server or here.
			CompletableFuture.runAsync(() -> {
				try {
					URI created = resource.get();

					if (created != null) {
						delete(created, bearerToken, timeout);
					}
				}
				catch (Exception e) {
					// The failure that ended the session is the one to report.
				}

				peer.close();
				result.completeExceptionally(PeerImpl.asWebRtcException(error,
						"The session with " + endpoint + " could not be set up"));
			}, rtc.controlPool);
		};

		peer.negotiator().createOffer(Gathering.COMPLETE)
				.thenApplyAsync(offer -> {
					Response response = post(endpoint, offer.sdp, bearerToken, timeout);
					resource.set(response.location);
					return response.body;
				}, rtc.controlPool)
				.thenCompose(answer -> peer.negotiator()
						.applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER, answer)))
				.thenCompose(applied -> peer.whenConnected())
				.whenComplete((connected, error) -> {
					if (error != null) {
						fail.accept(error);
					}
					else if (finished.compareAndSet(false, true)) {
						result.complete(resource.get());
					}
				});

		rtc.timer.schedule(() -> fail.accept(new WebRtcException("The session with " + endpoint
				+ " was not connected within " + timeout)), timeout.toMillis(), TimeUnit.MILLISECONDS);

		return result;
	}

	private static Response post(URI endpoint, String sdp, String bearerToken, Duration timeout) {
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
						+ ": " + read(connection.getErrorStream()));
			}

			String location = connection.getHeaderField("Location");

			return new Response(read(connection.getInputStream()),
					location == null ? null : endpoint.resolve(location));
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

	private static void delete(URI resource, String bearerToken, Duration timeout) throws IOException {
		HttpURLConnection connection = open(resource, "DELETE", bearerToken, timeout);

		try {
			connection.getResponseCode();
		}
		finally {
			connection.disconnect();
		}
	}

	private static HttpURLConnection open(URI uri, String method, String bearerToken, Duration timeout)
			throws IOException {
		HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
		connection.setRequestMethod(method);
		connection.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
		connection.setReadTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));

		if (bearerToken != null) {
			connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
		}

		return connection;
	}

	private static String read(InputStream in) throws IOException {
		if (in == null) {
			return "";
		}

		try (InputStream stream = in) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int count;

			while ((count = stream.read(buffer)) != -1) {
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



	private static final class Response {

		final String body;

		final URI location;


		Response(String body, URI location) {
			this.body = body;
			this.location = location;
		}
	}
}
