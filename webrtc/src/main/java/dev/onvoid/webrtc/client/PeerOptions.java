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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;

/**
 * How {@link WebRtc#createPeer} sets up a peer.
 * <pre>{@code
 * PeerOptions.builder()
 *         .iceServer("stun:stun.l.google.com:19302")
 *         .polite(true)
 *         .signaling(message -> socket.send(message.toJson()))
 *         .build();
 * }</pre>
 */
public final class PeerOptions {

	final String name;

	final List<RTCIceServer> iceServers;

	final boolean relayOnly;

	final Signaling signaling;

	final boolean polite;

	final Negotiation negotiation;

	final Set<MediaKind> receive;

	final Consumer<RTCConfiguration> customizer;


	private PeerOptions(Builder builder) {
		this.name = builder.name;
		this.iceServers = Collections.unmodifiableList(new ArrayList<>(builder.iceServers));
		this.relayOnly = builder.relayOnly;
		this.signaling = builder.signaling;
		this.polite = builder.polite;
		this.negotiation = builder.negotiation;
		this.receive = Collections.unmodifiableSet(builder.receive.isEmpty()
				? EnumSet.noneOf(MediaKind.class) : EnumSet.copyOf(builder.receive));
		this.customizer = builder.customizer;
	}

	/**
	 * @return a builder for a peer without ICE servers that negotiates
	 * automatically.
	 */
	public static Builder builder() {
		return new Builder();
	}

	RTCConfiguration toConfiguration() {
		RTCConfiguration config = new RTCConfiguration();

		for (RTCIceServer server : iceServers) {
			RTCIceServer copy = new RTCIceServer();
			copy.urls.addAll(server.urls);
			copy.username = server.username;
			copy.password = server.password;
			config.iceServers.add(copy);
		}

		if (relayOnly) {
			config.iceTransportPolicy = RTCIceTransportPolicy.RELAY;
		}

		// Perfect negotiation lets the polite peer apply a remote offer while
		// its own is pending, which rolls its own offer back.
		config.enableImplicitRollback = negotiation == Negotiation.AUTOMATIC;

		if (customizer != null) {
			customizer.accept(config);
		}

		return config;
	}



	/** Builds {@link PeerOptions}. */
	public static final class Builder {

		private final List<RTCIceServer> iceServers = new ArrayList<>();

		private final Set<MediaKind> receive = EnumSet.noneOf(MediaKind.class);

		private String name;

		private boolean relayOnly;

		private Signaling signaling;

		private boolean polite;

		private Negotiation negotiation = Negotiation.AUTOMATIC;

		private Consumer<RTCConfiguration> customizer;


		private Builder() {
		}

		/**
		 * @param name the name in thread names and messages.
		 *
		 * @return this builder.
		 */
		public Builder name(String name) {
			this.name = requireNonNull(name, "name");
			return this;
		}

		/**
		 * Adds a STUN or TURN server that needs no credentials.
		 *
		 * @param urls the server's URLs, such as {@code
		 *             stun:stun.l.google.com:19302}.
		 *
		 * @return this builder.
		 */
		public Builder iceServer(String... urls) {
			return iceServer(Arrays.asList(urls), null, null);
		}

		/**
		 * Adds a TURN server.
		 *
		 * @param url      the server's URL, such as {@code
		 *                 turn:turn.example.com:3478}.
		 * @param username the user name.
		 * @param password the password.
		 *
		 * @return this builder.
		 */
		public Builder iceServer(String url, String username, String password) {
			return iceServer(Collections.singletonList(url), requireNonNull(username, "username"),
					requireNonNull(password, "password"));
		}

		/**
		 * @param relayOnly true to connect through TURN servers only, which
		 *                  hides this side's addresses.
		 *
		 * @return this builder.
		 */
		public Builder relayOnly(boolean relayOnly) {
			this.relayOnly = relayOnly;
			return this;
		}

		/**
		 * @param signaling what carries messages to the remote peer. Required
		 *                  for {@link Negotiation#AUTOMATIC}.
		 *
		 * @return this builder.
		 */
		public Builder signaling(Signaling signaling) {
			this.signaling = requireNonNull(signaling, "signaling");
			return this;
		}

		/**
		 * Sets the role in perfect negotiation. When both peers send an offer
		 * at once, the polite one drops its own and answers the other. Exactly
		 * one of two peers must be polite; the default is impolite.
		 *
		 * @param polite true for the polite role.
		 *
		 * @return this builder.
		 */
		public Builder polite(boolean polite) {
			this.polite = polite;
			return this;
		}

		/**
		 * @param negotiation who drives the offer and answer exchange.
		 *
		 * @return this builder.
		 */
		public Builder negotiation(Negotiation negotiation) {
			this.negotiation = requireNonNull(negotiation, "negotiation");
			return this;
		}

		/**
		 * Receives audio or video without sending any, as a viewer does.
		 *
		 * @param kinds the kinds to receive.
		 *
		 * @return this builder.
		 */
		public Builder receive(MediaKind... kinds) {
			for (MediaKind kind : kinds) {
				receive.add(requireNonNull(kind, "kind"));
			}
			return this;
		}

		/**
		 * Changes anything else of the {@link RTCConfiguration} the peer is
		 * created with, after this builder applied its own values.
		 *
		 * @param customizer changes the configuration.
		 *
		 * @return this builder.
		 */
		public Builder configure(Consumer<RTCConfiguration> customizer) {
			this.customizer = requireNonNull(customizer, "customizer");
			return this;
		}

		/**
		 * @return the options.
		 *
		 * @throws IllegalStateException if the peer negotiates automatically
		 *                               and has no signaling.
		 */
		public PeerOptions build() {
			if (negotiation == Negotiation.AUTOMATIC && signaling == null) {
				throw new IllegalStateException("A peer that negotiates automatically needs a Signaling. "
						+ "Set one, or use Negotiation.MANUAL to drive the exchange through Peer.negotiator()");
			}

			return new PeerOptions(this);
		}

		private Builder iceServer(List<String> urls, String username, String password) {
			if (urls.isEmpty()) {
				throw new IllegalArgumentException("An ICE server needs at least one URL");
			}

			RTCIceServer server = new RTCIceServer();

			for (String url : urls) {
				requireNonNull(url, "url");

				if (!url.startsWith("stun:") && !url.startsWith("stuns:")
						&& !url.startsWith("turn:") && !url.startsWith("turns:")) {
					throw new IllegalArgumentException("An ICE server URL starts with stun:, stuns:, turn: or turns:, got " + url);
				}

				server.urls.add(url);
			}

			server.username = username;
			server.password = password;
			iceServers.add(server);

			return this;
		}
	}
}
