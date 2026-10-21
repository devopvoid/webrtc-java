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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes tracks to a server with the WebRTC HTTP Ingestion Protocol (WHIP,
 * RFC 9725), as media servers and streaming services take it:
 * <pre>{@code
 * Whip.Session session = Whip.to(URI.create("https://example.com/live/whip"))
 *         .bearerToken(streamKey)
 *         .track(rtc.media().camera())
 *         .track(rtc.media().microphone())
 *         .publish(rtc)
 *         .get(15, TimeUnit.SECONDS);
 * ...
 * session.close();
 * }</pre>
 * The offer goes to the endpoint with all local candidates, as WHIP servers
 * expect, and the session's future completes once the peer is connected.
 * Closing the session ends it on the server; the tracks stay open.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9725">RFC 9725: WHIP</a>
 */
public final class Whip {

	private Whip() {
	}

	/**
	 * @param endpoint the WHIP endpoint, an http or https URL.
	 *
	 * @return a builder that publishes to the endpoint.
	 */
	public static Builder to(URI endpoint) {
		return new Builder(HttpSession.checkEndpoint(endpoint));
	}



	/** Tracks published over WHIP. Closing it ends the session on the server. */
	public static final class Session extends HttpSession {

		Session(WebRtc rtc, Peer peer, URI resource, String bearerToken, Duration timeout) {
			super(rtc, peer, resource, bearerToken, timeout);
		}
	}



	/** Builds a WHIP {@link Session}. */
	public static final class Builder {

		private final URI endpoint;

		private final List<String[]> iceServers = new ArrayList<>();

		private final List<LocalTrack> tracks = new ArrayList<>();

		private String bearerToken;

		private Duration timeout = Duration.ofSeconds(15);


		private Builder(URI endpoint) {
			this.endpoint = endpoint;
		}

		/**
		 * @param track a track of the runtime to publish. Tracks share one
		 *              stream, so the server keeps audio and video in sync.
		 *
		 * @return this builder.
		 */
		public Builder track(LocalTrack track) {
			tracks.add(requireNonNull(track, "track"));
			return this;
		}

		/**
		 * @param token the token the endpoint authorizes the request with,
		 *              sent as {@code Authorization: Bearer <token>}; for many
		 *              services, the stream key.
		 *
		 * @return this builder.
		 */
		public Builder bearerToken(String token) {
			this.bearerToken = requireNonNull(token, "token");
			return this;
		}

		/**
		 * @param urls a STUN or TURN server without credentials.
		 *
		 * @return this builder.
		 */
		public Builder iceServer(String... urls) {
			iceServers.add(urls.clone());
			return this;
		}

		/**
		 * @param timeout how long the session may take to be connected; 15
		 *                seconds by default.
		 *
		 * @return this builder.
		 */
		public Builder timeout(Duration timeout) {
			requireNonNull(timeout, "timeout");

			if (timeout.isNegative() || timeout.isZero()) {
				throw new IllegalArgumentException("The timeout must be positive, got " + timeout);
			}

			this.timeout = timeout;
			return this;
		}

		/**
		 * Creates a peer of the runtime that sends the tracks and negotiates it
		 * with the endpoint.
		 *
		 * @param rtc the runtime that owns the peer and the tracks.
		 *
		 * @return a future with the session once its peer is connected. It
		 * fails if the endpoint refuses the offer or the peer does not connect
		 * in time, with nothing left behind on the server.
		 *
		 * @throws IllegalStateException if no track was given.
		 */
		public CompletableFuture<Session> publish(WebRtc rtc) {
			requireNonNull(rtc, "rtc");

			if (tracks.isEmpty()) {
				throw new IllegalStateException("Give at least one track to publish");
			}

			PeerOptions.Builder options = PeerOptions.builder()
					.name("whip-" + endpoint.getHost())
					.negotiation(Negotiation.MANUAL);

			for (String[] urls : iceServers) {
				options.iceServer(urls);
			}

			PeerImpl peer = (PeerImpl) rtc.createPeer(options.build());

			for (LocalTrack track : tracks) {
				peer.addTrack(track);
			}

			return peer.toPublic(HttpSession.negotiate(rtc, peer, endpoint, bearerToken, timeout)
					.thenApply(resource -> new Session(rtc, peer, resource, bearerToken, timeout)));
		}
	}
}
