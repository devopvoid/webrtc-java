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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
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
 * By default the offer goes to the endpoint at once, and candidates follow by
 * PATCH, gathered with the STUN and TURN servers the endpoint names. The
 * session's future completes once the peer is connected. The session
 * restarts ICE when the connection breaks, or by {@link Session#restartIce()}.
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
	 * Its extensions have relation types under urn:ietf:params:whep:ext
	 * (4.10).
	 */
	private static final HttpSession.Protocol PROTOCOL = new HttpSession.Protocol(Set.of(409, 503), true,
			"urn:ietf:params:whep:ext:");

	/** The profile a WHEP endpoint links to (draft-ietf-wish-whep-05, 4.2). */
	private static final String PROFILE = "urn:ietf:params:whep";


	private Whep() {
	}

	/**
	 * Tells from the answer to HEAD whether an endpoint serves WHEP
	 * (draft-ietf-wish-whep-05, 4.2): it answers 200, with the Content-Type
	 * application/sdp and a Link to the WHEP profile with the relation
	 * "profile". The Content-Type alone does not count.
	 */
	static boolean servesWhep(HttpCall.Response response) {
		String type = response.header("Content-Type");

		if (response.status != 200 || type == null
				|| !type.split(";", 2)[0].trim().equalsIgnoreCase("application/sdp")) {
			return false;
		}

		boolean[] profile = new boolean[1];

		HttpIce.forEachLink(response.headers.allValues("Link"), (target, parameters) -> {
			for (String relation : HttpIce.relations(parameters)) {
				profile[0] |= relation.equalsIgnoreCase("profile") && target.equalsIgnoreCase(PROFILE);
			}
		});

		return profile[0];
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

		Session(WebRtc rtc, Peer peer, URI resource, Map<String, URI> extensions, String authorization,
				Duration timeout, HttpIce ice) {
			super(rtc, peer, resource, extensions, authorization, timeout, ice);
		}
	}



	/** Builds a WHEP {@link Session}. */
	public static final class Builder {

		private final URI endpoint;

		private final PeerOptions.Builder options = PeerOptions.builder()
				.negotiation(Negotiation.MANUAL).configure(HttpSession::requireBundle);

		private final Set<MediaKind> receive = EnumSet.of(MediaKind.VIDEO, MediaKind.AUDIO);

		private final Map<MediaKind, List<String>> codecs = new EnumMap<>(MediaKind.class);

		/** The value of the Authorization header, or null. */
		private String authorization;

		private Gathering gathering = Gathering.TRICKLE;

		private Duration timeout = Duration.ofSeconds(15);


		private Builder(URI endpoint) {
			this.endpoint = endpoint;
			options.name("whep-" + endpoint.getHost());
		}

		/**
		 * @param token the token the endpoint authorizes the requests with,
		 *              sent as {@code Authorization: Bearer <token>}. It
		 *              replaces the credentials of {@link #basicAuth}.
		 *
		 * @return this builder.
		 */
		public Builder bearerToken(String token) {
			this.authorization = HttpSession.bearer(token);
			return this;
		}

		/**
		 * Authorizes the requests with a user and password, by HTTP Basic
		 * authentication (RFC 7617), as servers such as MediaMTX take them.
		 * They go with every request of the session, as {@code
		 * Authorization: Basic}, in UTF-8, and replace a {@link #bearerToken}.
		 *
		 * @param user     the user, without a colon.
		 * @param password the password.
		 *
		 * @return this builder.
		 *
		 * @throws IllegalArgumentException if the user contains a colon.
		 */
		public Builder basicAuth(String user, String password) {
			this.authorization = HttpSession.basic(user, password);
			return this;
		}

		/**
		 * Receives video only with the codecs of the given names, in this
		 * order of preference, so that the endpoint sends one of them, such as
		 * H.264 for a decoder in hardware. This holds for the answer to a
		 * counter-offer as well. By default, the offer holds every video codec
		 * WebRTC has, in its own order, and the endpoint picks.
		 *
		 * @param names codec names, such as {@code H264}, {@code VP8},
		 *              {@code VP9} or {@code AV1}, or MIME types, such as
		 *              {@code video/H264}; case does not matter.
		 *
		 * @return this builder.
		 */
		public Builder videoCodecs(String... names) {
			codecs.put(MediaKind.VIDEO, HttpSession.codecNames(names));
			return this;
		}

		/**
		 * Receives audio only with the codecs of the given names, in this
		 * order of preference, as {@link #videoCodecs} does for video.
		 *
		 * @param names codec names, such as {@code opus}, {@code G722} or
		 *              {@code PCMU}, or MIME types, such as {@code audio/opus};
		 *              case does not matter.
		 *
		 * @return this builder.
		 */
		public Builder audioCodecs(String... names) {
			codecs.put(MediaKind.AUDIO, HttpSession.codecNames(names));
			return this;
		}

		/**
		 * @param urls a STUN or TURN server without credentials. Servers given
		 *             here replace those the endpoint names in its answer.
		 *
		 * @return this builder.
		 */
		public Builder iceServer(String... urls) {
			options.iceServer(urls);
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
		 * Configures an authenticated TURN server, overriding endpoint-provided
		 * servers. Uses a distinct name so three URL arguments to iceServer
		 * keep their original meaning.
		 *
		 * @param url the TURN server URL.
		 * @param username the TURN username.
		 * @param password the TURN password.
		 * @return this builder.
		 */
		public Builder turnServer(String url, String username, String password) {
			options.iceServer(url, username, password);
			return this;
		}

		/**
		 * Selects when the initial offer is sent. COMPLETE gathers candidates
		 * before POST, up to the peer's gathering timeout (three seconds).
		 * Later candidates may still follow by PATCH. Configure STUN/TURN
		 * servers locally in that mode: links in the answer arrive too late
		 * for the initial gathering. Subsequent ICE restarts still use PATCH.
		 *
		 * @param gathering TRICKLE (the default) or COMPLETE.
		 * @return this builder.
		 */
		public Builder gathering(Gathering gathering) {
			this.gathering = requireNonNull(gathering, "gathering");
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
		 * Sets the transform that every encoded frame received passes through,
		 * after the depacketizer and before the decoder, as end-to-end
		 * encryption needs. It is in place before the session hands out a
		 * track, including the tracks of a counter-offer, so it sees the first
		 * frame. See {@link PeerOptions.Builder#receiveTransform}.
		 *
		 * @param transform the transform of every receiver.
		 *
		 * @return this builder.
		 */
		public Builder receiveTransform(RTCEncodedFrameTransformer transform) {
			options.receiveTransform(transform);
			return this;
		}

		/**
		 * Asks the endpoint with HEAD whether it serves WHEP, as a player may
		 * before it plays a URL it was given (draft-ietf-wish-whep-05, 4.2): a
		 * WHEP endpoint answers 200, with the Content-Type {@code
		 * application/sdp} and a Link to the profile {@code
		 * urn:ietf:params:whep}. The Content-Type alone does not count. The
		 * request carries the credentials of this builder, takes its timeout
		 * at most, and follows the redirects the offer follows.
		 *
		 * @param rtc the runtime whose HTTP client sends the request.
		 *
		 * @return a future with whether the endpoint serves WHEP; false for one
		 * that answers otherwise, such as one that takes no HEAD. It fails with
		 * a {@link WebRtcException} if the request does, as when the endpoint
		 * cannot be reached in time.
		 */
		public CompletableFuture<Boolean> detect(WebRtc rtc) {
			requireNonNull(rtc, "rtc");

			return ClientFuture.copyOf(HttpSession.head(rtc, endpoint, authorization, timeout)
					.thenApply(Whep::servesWhep));
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
		 * the session the same way. It fails with a {@link
		 * ConfigurationException} if WebRTC has no codec of a name given.
		 */
		public CompletableFuture<Session> play(WebRtc rtc) {
			requireNonNull(rtc, "rtc");

			options.receive(receive.toArray(new MediaKind[0]));

			// The builder may change while the session starts; the session keeps
			// what it started with, so that its DELETE is authorized like its POST.
			String credentials = authorization;
			Duration limit = timeout;
			Map<MediaKind, List<String>> names = new EnumMap<>(codecs);
			PeerImpl peer = (PeerImpl) rtc.createPeer(options.build());

			try {
				return HttpSession.start(rtc, peer, endpoint, credentials, limit, peer.preferCodecs(names), gathering,
						PROTOCOL, Session::new);
			}
			catch (RuntimeException | Error e) {
				// Until the session has it, no one else would close the peer.
				peer.close();
				throw e;
			}
		}
	}
}
