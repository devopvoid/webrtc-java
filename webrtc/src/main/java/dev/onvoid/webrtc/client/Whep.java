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
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.internal.Timeouts;

/**
 * Receives a stream from a server with the WebRTC HTTP Egress Protocol (WHEP),
 * as media servers such as MediaMTX, Janus and Cloudflare Stream offer it:
 * <pre>{@code
 * Whep.Session session = Whep.from(URI.create("https://example.com/live/whep"))
 *         .play(rtc)
 *         .get(15, TimeUnit.SECONDS);
 *
 * session.peer().onTrack(track -> ...);
 * ...
 * session.close();
 * }</pre>
 * The offer goes to the endpoint with all local candidates, as WHEP servers
 * expect, and the session's future completes once the peer is connected.
 * {@link Peer#onTrack} hands over tracks that arrived before a handler was
 * added, so registering it after the future completed misses none.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9725">WHIP</a> and the WHEP draft that builds on it
 */
public final class Whep {

	/**
	 * An endpoint under load answers 503, and one whose stream has yet to
	 * start 409, both with Retry-After; and an endpoint may counter the offer
	 * with one of its own (draft-ietf-wish-whep-05, 4.7, 4.3.9 and 4.3.2).
	 */
	private static final HttpSession.Protocol PROTOCOL = new HttpSession.Protocol(Set.of(409, 503), true);


	private Whep() {
	}

	/**
	 * @param endpoint the WHEP endpoint, an https URL, or an http one on this
	 *                 machine: {@code localhost}, 127.0.0.0/8 or {@code ::1}.
	 *
	 * @return a builder that receives video and audio from the endpoint.
	 *
	 * @throws IllegalArgumentException if the endpoint is neither.
	 */
	public static Builder from(URI endpoint) {
		return new Builder(HttpSession.checkEndpoint(endpoint));
	}



	/** A stream received over WHEP. Closing it ends the session on the server. */
	public static final class Session extends HttpSession {

		Session(WebRtc rtc, Peer peer, URI resource, String bearerToken, Duration timeout) {
			super(rtc, peer, resource, bearerToken, timeout);
		}
	}



	/** Builds a WHEP {@link Session}. */
	public static final class Builder {

		private final URI endpoint;

		private final List<String[]> iceServers = new ArrayList<>();

		private final Set<MediaKind> receive = EnumSet.of(MediaKind.VIDEO, MediaKind.AUDIO);

		private String bearerToken;

		private Duration timeout = Duration.ofSeconds(15);


		private Builder(URI endpoint) {
			this.endpoint = endpoint;
		}

		/**
		 * @param token the token the endpoint authorizes the request with,
		 *              sent as {@code Authorization: Bearer <token>}.
		 *
		 * @return this builder.
		 */
		public Builder bearerToken(String token) {
			this.bearerToken = requireNonNull(token, "token");
			return this;
		}

		/**
		 * @param urls a STUN or TURN server without credentials. The endpoint
		 *             usually names its own in its answer.
		 *
		 * @return this builder.
		 */
		public Builder iceServer(String... urls) {
			iceServers.add(urls.clone());
			return this;
		}

		/**
		 * @param kinds what to receive; video and audio by default.
		 *
		 * @return this builder.
		 */
		public Builder receive(MediaKind... kinds) {
			if (kinds.length == 0) {
				throw new IllegalArgumentException("Receive at least one kind of media");
			}

			receive.clear();

			for (MediaKind kind : kinds) {
				receive.add(requireNonNull(kind, "kind"));
			}
			return this;
		}

		/**
		 * @param timeout how long the session may take to be connected, and its
		 *                deletion to be answered; 15 seconds by default.
		 *
		 * @return this builder.
		 */
		public Builder timeout(Duration timeout) {
			this.timeout = Timeouts.requirePositive(timeout, "timeout");
			return this;
		}

		/**
		 * Creates a receive-only peer of the runtime and negotiates it with the
		 * endpoint.
		 *
		 * @param rtc the runtime that owns the peer.
		 *
		 * @return a future with the session once its peer is connected. It
		 * fails if the endpoint refuses the offer or the peer does not connect
		 * in time, with nothing left behind on the server. Cancelling it ends
		 * the session the same way.
		 */
		public CompletableFuture<Session> play(WebRtc rtc) {
			requireNonNull(rtc, "rtc");

			PeerOptions.Builder options = PeerOptions.builder()
					.name("whep-" + endpoint.getHost())
					.negotiation(Negotiation.MANUAL)
					.receive(receive.toArray(new MediaKind[0]));

			for (String[] urls : iceServers) {
				options.iceServer(urls);
			}

			// The builder may change while the session starts; the session keeps
			// what it started with, so that its DELETE is authorized like its POST.
			String token = bearerToken;
			Duration limit = timeout;
			PeerImpl peer = (PeerImpl) rtc.createPeer(options.build());

			try {
				return HttpSession.start(rtc, peer, endpoint, token, limit, PROTOCOL, Session::new);
			}
			catch (RuntimeException | Error e) {
				// Until the session has it, no one else would close the peer.
				peer.close();
				throw e;
			}
		}
	}
}
