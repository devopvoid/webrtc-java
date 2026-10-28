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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.internal.Timeouts;

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
 * By default the offer goes to the endpoint at once, and candidates follow by
 * PATCH, gathered with the STUN and TURN servers the endpoint names. The
 * session's future completes once the peer is connected. The session
 * restarts ICE when the connection breaks, or by {@link Session#restartIce()}.
 * Closing the session ends it on the server; the tracks stay open.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9725">RFC 9725: WHIP</a>
 */
public final class Whip {

	/**
	 * An endpoint under load answers 503, with Retry-After (RFC 9725, 4.5).
	 * WHIP has no counter-offers.
	 */
	private static final HttpSession.Protocol PROTOCOL = new HttpSession.Protocol(Set.of(503), false);


	private Whip() {
	}

	/**
	 * @param endpoint the WHIP endpoint, an https URL, or an http one on this
	 *                 machine: {@code localhost}, 127.0.0.0/8 or {@code ::1}.
	 *
	 * @return a builder that publishes to the endpoint.
	 *
	 * @throws IllegalArgumentException if the endpoint is neither.
	 */
	public static Builder to(URI endpoint) {
		return new Builder(HttpSession.checkEndpoint(endpoint));
	}



	/** Tracks published over WHIP. Closing it ends the session on the server. */
	public static final class Session extends HttpSession {

		Session(WebRtc rtc, Peer peer, URI resource, String bearerToken, Duration timeout, HttpIce ice) {
			super(rtc, peer, resource, bearerToken, timeout, ice);
		}
	}



	/** Builds a WHIP {@link Session}. */
	public static final class Builder {

		private final URI endpoint;

		private final PeerOptions.Builder options = PeerOptions.builder()
				.negotiation(Negotiation.MANUAL).configure(HttpSession::requireBundle);

		private final List<LocalTrack> tracks = new ArrayList<>();

		private final Map<MediaKind, List<String>> codecs = new EnumMap<>(MediaKind.class);

		private String bearerToken;

		private boolean alwaysOfferVideo;

		private Gathering gathering = Gathering.TRICKLE;

		private Duration timeout = Duration.ofSeconds(15);


		private Builder(URI endpoint) {
			this.endpoint = endpoint;
			options.name("whip-" + endpoint.getHost());
		}

		/**
		 * @param track a track of the runtime to publish. Tracks share one
		 *              stream, so the server keeps audio and video in sync,
		 *              which holds one audio and one video track at most.
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
		 *              services, the stream key. Servers that ask for a user
		 *              and password, such as MediaMTX, take them this way as
		 *              {@code user:password}.
		 *
		 * @return this builder.
		 */
		public Builder bearerToken(String token) {
			this.bearerToken = requireNonNull(token, "token");
			return this;
		}

		/**
		 * Offers only the video codecs of the given names, in this order of
		 * preference, for endpoints that take only some, such as H.264 alone.
		 * Retransmission and FEC go along. By default, the offer holds every
		 * video codec WebRTC has, in its own order, and the endpoint picks.
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
		 * Offers only the audio codecs of the given names, in this order of
		 * preference. Redundancy, comfort noise and DTMF go along. By default,
		 * the offer holds every audio codec WebRTC has, in its own order.
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
		 * Offers video even when no video track is published, for endpoints
		 * that refuse an offer without video, such as Amazon IVS. The offer
		 * then holds video that only sends, in the stream of the tracks, and
		 * nothing is sent on it. With a video track, this changes nothing.
		 *
		 * @return this builder.
		 */
		public Builder alwaysOfferVideo() {
			this.alwaysOfferVideo = true;
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
		 * Creates a peer of the runtime that sends the tracks and negotiates it
		 * with the endpoint.
		 *
		 * @param rtc the runtime that owns the peer and the tracks.
		 *
		 * @return a future with the session once its peer is connected. It
		 * fails if the endpoint refuses the offer or the peer does not connect
		 * in time, with nothing left behind on the server. Cancelling it ends
		 * the session the same way. It fails with a {@link
		 * ConfigurationException} if WebRTC has no codec of a name given.
		 *
		 * @throws IllegalStateException    if no track was given.
		 * @throws IllegalArgumentException if a track was not created by the
		 *                                  runtime, or two tracks are of the
		 *                                  same kind.
		 */
		public CompletableFuture<Session> publish(WebRtc rtc) {
			requireNonNull(rtc, "rtc");

			if (tracks.isEmpty()) {
				throw new IllegalStateException("Give at least one track to publish");
			}

			// Before the peer exists, so that a wrong track leaves nothing behind.
			Set<MediaKind> kinds = EnumSet.noneOf(MediaKind.class);

			for (LocalTrack track : tracks) {
				if (!(track instanceof LocalTrackImpl) || ((LocalTrackImpl) track).rtc != rtc) {
					throw new IllegalArgumentException("The track " + track + " was not created by the runtime");
				}
				if (!kinds.add(track.kind())) {
					// RFC 9725, 4.4.2: one stream, with one track of each kind.
					throw new IllegalArgumentException("WHIP publishes at most one "
							+ track.kind().name().toLowerCase(Locale.ROOT) + " track, got another: " + track);
				}
			}

			// The builder may change while the session starts; the session keeps
			// what it started with, so that its DELETE is authorized like its POST.
			String token = bearerToken;
			Duration limit = timeout;
			Map<MediaKind, List<String>> names = new EnumMap<>(codecs);
			boolean placeholder = alwaysOfferVideo && !kinds.contains(MediaKind.VIDEO);
			PeerImpl peer = (PeerImpl) rtc.createPeer(options.build());

			try {
				for (LocalTrack track : tracks) {
					peer.addTrack(track);
				}

				CompletableFuture<Void> video = placeholder
						? peer.addVideoWithoutTrack()
						: CompletableFuture.completedFuture(null);

				// Before the offer, which follows in the same order: WHIP only
				// sends (RFC 9725, 4.2). Should WebRTC refuse, the offer stays
				// sendrecv, which WHIP allows as well.
				peer.sendOnly();

				CompletableFuture<Void> prepared = CompletableFuture.allOf(video, peer.preferCodecs(names));

				return HttpSession.start(rtc, peer, endpoint, token, limit, prepared, gathering, PROTOCOL,
						Session::new);
			}
			catch (RuntimeException | Error e) {
				// Until the session has it, no one else would close the peer.
				peer.close();
				throw e;
			}
		}
	}
}
