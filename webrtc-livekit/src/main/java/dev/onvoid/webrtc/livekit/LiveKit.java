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

package dev.onvoid.webrtc.livekit;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.client.WebRtc;

/**
 * Joins LiveKit rooms over the peers of a {@link WebRtc} runtime.
 * <pre>{@code
 * Room room = LiveKit.room(rtc, URI.create("wss://live.example.com"), token)
 *         .connect()
 *         .get();
 * }</pre>
 * The token comes from the application's backend, which signs it with the
 * server's API secret; this module creates none.
 */
public final class LiveKit {

	/** The version this client reports to the server. */
	static final String VERSION = "0.1.0";

	/**
	 * The signaling protocol version this client speaks, with a publisher and
	 * a subscriber connection.
	 */
	static final int PROTOCOL = 16;

	private LiveKit() {
	}

	/**
	 * @param rtc   the runtime that owns the peers and the tracks.
	 * @param url   the server's URL, such as wss://live.example.com.
	 * @param token the access token for the room.
	 *
	 * @return a builder of the connection.
	 */
	public static Builder room(WebRtc rtc, URI url, String token) {
		return new Builder(rtc, url, token);
	}



	/**
	 * Builds the connection to a room.
	 */
	public static final class Builder {

		private final WebRtc rtc;

		private final URI url;

		private final String token;

		private boolean autoSubscribe = true;

		private Duration timeout = Duration.ofSeconds(10);


		private Builder(WebRtc rtc, URI url, String token) {
			this.rtc = requireNonNull(rtc, "rtc");
			this.url = requireNonNull(url, "url");
			this.token = requireNonNull(token, "token");
		}

		/**
		 * @param autoSubscribe true, the default, to receive every track
		 *                      published in the room.
		 *
		 * @return this builder.
		 */
		public Builder autoSubscribe(boolean autoSubscribe) {
			this.autoSubscribe = autoSubscribe;
			return this;
		}

		/**
		 * @param timeout how long joining and each request to the server may
		 *                take, ten seconds by default.
		 *
		 * @return this builder.
		 */
		public Builder timeout(Duration timeout) {
			requireNonNull(timeout, "timeout");
			if (timeout.isNegative() || timeout.isZero()) {
				throw new IllegalArgumentException("The timeout must be positive");
			}
			this.timeout = timeout;
			return this;
		}

		/**
		 * Opens the signaling connection and joins the room.
		 *
		 * @return a future with the room once joined, which fails if the
		 * server refuses the token or does not answer in time.
		 */
		public CompletableFuture<Room> connect() {
			return RoomImpl.connect(rtc, url, token, autoSubscribe, timeout);
		}
	}

}
