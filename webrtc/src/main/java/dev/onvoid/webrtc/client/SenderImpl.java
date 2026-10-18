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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpSender;

/**
 * The {@link Sender} of a {@link PeerImpl}. The native sender is used on the
 * peer's control executor only.
 */
final class SenderImpl implements Sender {

	private final PeerImpl peer;

	private final MediaKind kind;

	private volatile LocalTrackImpl track;

	// Confined to the peer's control executor.

	private RTCPeerConnection connection;

	private RTCRtpSender sender;

	private boolean removed;

	private Throwable attachError;


	SenderImpl(PeerImpl peer, LocalTrackImpl track) {
		this.peer = peer;
		this.track = track;
		this.kind = track.kind();
	}

	void attach(RTCPeerConnection connection, List<String> streamIds) {
		LocalTrackImpl local = track;

		if (!local.register(this)) {
			throw new IllegalStateException("The track " + local.id() + " is closed");
		}

		try {
			RTCRtpSender added = connection.addTrack(local.nativeTrack(), streamIds);

			if (added == null) {
				throw new WebRtcException("WebRTC refused the track " + local.id());
			}

			this.connection = connection;
			this.sender = added;
		}
		catch (RuntimeException e) {
			local.unregister(this);
			throw e;
		}
	}

	void failAttach(Throwable error) {
		attachError = error;
		removed = true;
		track = null;
	}

	@Override
	public LocalTrack track() {
		return track;
	}

	@Override
	public CompletableFuture<Void> replaceTrack(LocalTrack replacement) {
		LocalTrackImpl next = replacement == null ? null : checkTrack(replacement);

		return run(() -> {
			if (next != null && !next.register(this)) {
				throw new IllegalStateException("The track " + next.id() + " is closed");
			}

			try {
				sender.replaceTrack(next == null ? null : next.nativeTrack());
			}
			catch (RuntimeException e) {
				if (next != null) {
					next.unregister(this);
				}
				throw e;
			}

			LocalTrackImpl previous = track;
			track = next;

			if (previous != null && previous != next) {
				previous.unregister(this);
			}
		});
	}

	@Override
	public CompletableFuture<Void> configure(Encoding encoding) {
		if (encoding == null) {
			throw new NullPointerException("encoding");
		}

		return run(() -> {
			RTCRtpSendParameters parameters = sender.getParameters();

			for (RTCRtpEncodingParameters values : parameters.encodings) {
				if (encoding.active != null) {
					values.active = encoding.active;
				}
				if (encoding.maxBitrate != null) {
					values.maxBitrate = encoding.maxBitrate;
				}
				if (kind == MediaKind.VIDEO) {
					if (encoding.maxFrameRate != null) {
						values.maxFramerate = encoding.maxFrameRate;
					}
					if (encoding.scaleDownBy != null) {
						values.scaleResolutionDownBy = encoding.scaleDownBy;
					}
				}
			}

			sender.setParameters(parameters);
		});
	}

	@Override
	public CompletableFuture<Void> remove() {
		return run(() -> {
			connection.removeTrack(sender);
			release();
			peer.forgetSender(this);
		});
	}

	@Override
	public String toString() {
		LocalTrackImpl current = track;

		return "Sender[" + kind + ", " + (current == null ? "no track" : current.id()) + "]";
	}

	/**
	 * Stops sending a track that closes.
	 *
	 * @return a future that completes once the sender let go of the track.
	 */
	CompletableFuture<Void> trackClosed(LocalTrackImpl closedTrack) {
		CompletableFuture<Void> done = new CompletableFuture<>();

		peer.control.execute(() -> {
			try {
				if (!removed && sender != null && track == closedTrack && !peer.isClosed()) {
					sender.replaceTrack(null);
				}
			}
			catch (Throwable e) {
				peer.rtc.reportError(e);
			}
			finally {
				if (track == closedTrack) {
					track = null;
				}
				done.complete(null);
			}
		});

		return done;
	}

	/**
	 * Lets go of the native sender while the peer closes, on its control
	 * executor.
	 */
	void closeFromPeer() {
		release();
	}

	private void release() {
		removed = true;

		if (sender != null) {
			sender.dispose();
			sender = null;
		}

		LocalTrackImpl previous = track;
		track = null;

		if (previous != null) {
			previous.unregister(this);
		}
	}

	private LocalTrackImpl checkTrack(LocalTrack replacement) {
		if (!(replacement instanceof LocalTrackImpl) || ((LocalTrackImpl) replacement).rtc != peer.rtc) {
			throw new IllegalArgumentException("The track was not created by the runtime of " + peer.name);
		}
		if (replacement.kind() != kind) {
			throw new IllegalArgumentException("A " + kind + " sender cannot send a " + replacement.kind() + " track");
		}

		return (LocalTrackImpl) replacement;
	}

	private CompletableFuture<Void> run(Runnable operation) {
		CompletableFuture<Void> result = new CompletableFuture<>();

		peer.control.execute(() -> {
			try {
				if (peer.isClosed()) {
					throw peer.closedError();
				}
				if (attachError != null) {
					throw PeerImpl.asWebRtcException(attachError, "The track was never added");
				}
				if (removed) {
					throw new IllegalStateException("The sender was removed");
				}

				operation.run();
				result.complete(null);
			}
			catch (Throwable e) {
				result.completeExceptionally(e instanceof IllegalStateException || e instanceof IllegalArgumentException
						? e : PeerImpl.asWebRtcException(e, "Changing the sender failed"));
			}
		});

		return peer.toPublic(result);
	}
}
