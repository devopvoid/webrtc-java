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
import java.util.List;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCDataChannel;

/**
 * The data channels of a peer, those it created and those the remote peer
 * opened.
 * <p>
 * What is open is confined to the control executor, and what the handlers have
 * been told so far to the event executor, as the methods say. A handler added
 * late hears of every channel the remote peer opened that is still open.
 */
final class DataChannels {

	private final PeerImpl peer;

	// Confined to the control executor.

	private final List<DataChannelImpl> channels = new ArrayList<>();

	// Confined to the event executor.

	private final List<DataChannel> delivered = new ArrayList<>();

	private final Handlers<DataChannel> handlers;


	DataChannels(PeerImpl peer) {
		this.peer = peer;
		this.handlers = new Handlers<>(peer.rtc::reportError);
	}

	/**
	 * Creates a channel of this side. The native channel follows on the
	 * control executor.
	 */
	DataChannel create(String label, ChannelOptions options) {
		requireNonNull(label, "label");
		requireNonNull(options, "options");

		DataChannelImpl channel = new DataChannelImpl(peer, label);

		peer.control.execute(() -> {
			if (peer.isClosed()) {
				channel.closeFromPeer(peer.closedError());
				return;
			}

			try {
				RTCDataChannel nativeChannel = peer.connection().createDataChannel(label, options.toInit());

				if (nativeChannel == null) {
					throw new ConfigurationException("WebRTC refused the data channel " + label);
				}

				channels.add(channel);
				channel.bind(nativeChannel);
			}
			catch (Throwable e) {
				WebRtcException error = PeerImpl.asWebRtcException(e, "Creating the data channel " + label + " failed");
				channel.closeFromPeer(error);
				peer.deliverError(error);
			}
		});

		return channel;
	}

	Subscription onChannel(Consumer<DataChannel> handler) {
		requireNonNull(handler, "handler");

		return peer.subscribe(() -> {
			for (DataChannel channel : new ArrayList<>(delivered)) {
				peer.call(handler, channel);
			}
			return handlers.add(handler);
		});
	}

	/**
	 * Takes a channel the remote peer opened. On the control executor.
	 */
	void opened(DataChannelImpl channel) {
		if (peer.isClosed()) {
			channel.closeFromPeer(peer.closedError());
			return;
		}

		channels.add(channel);

		peer.events.execute(() -> {
			delivered.add(channel);
			handlers.accept(channel);
		});
	}

	/**
	 * Forgets a channel that closed. On the control executor.
	 */
	void closed(DataChannelImpl channel) {
		channels.remove(channel);

		peer.events.execute(() -> delivered.remove(channel));
	}

	/**
	 * Closes every channel, as the peer does when it closes. On the control
	 * executor.
	 */
	void closeAll(PeerClosedException error) {
		for (DataChannelImpl channel : new ArrayList<>(channels)) {
			channel.closeFromPeer(error);
		}
		channels.clear();
	}

	/**
	 * Forgets the channels the handlers heard of. On the event executor.
	 */
	void clearDelivered() {
		delivered.clear();
	}

	/**
	 * Lets go of the handlers. On the event executor.
	 */
	void clearHandlers() {
		handlers.clear();
	}
}
