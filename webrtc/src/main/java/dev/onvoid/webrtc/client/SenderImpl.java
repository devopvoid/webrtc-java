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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCDtmfSender;
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

	private RTCDtmfSender dtmf;

	/** Completes once the tones sent last have been played. */
	private CompletableFuture<Void> pendingTones;

	// Confined to the peer's event executor.

	private final Handlers<String> toneHandlers;


	SenderImpl(PeerImpl peer, LocalTrackImpl track) {
		this.peer = peer;
		this.track = track;
		this.kind = track.kind();
		this.toneHandlers = new Handlers<>(peer.rtc::reportError);
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
	public CompletableFuture<Void> sendTones(String tones) {
		return sendTones(tones, Duration.ofMillis(100), Duration.ofMillis(70));
	}

	@Override
	public CompletableFuture<Void> sendTones(String tones, Duration duration, Duration gap) {
		requireNonNull(tones, "tones");
		requireNonNull(duration, "duration");
		requireNonNull(gap, "gap");

		long durationMs = duration.toMillis();
		long gapMs = gap.toMillis();

		if (durationMs < 40 || durationMs > 6000) {
			throw new IllegalArgumentException("A tone lasts 40 ms to 6 seconds, got " + duration);
		}
		if (gapMs < 30 || gapMs > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("The gap between tones is at least 30 ms, got " + gap);
		}

		CompletableFuture<Void> sent = new CompletableFuture<>();

		peer.control.execute(() -> {
			try {
				checkUsable();

				if (kind != MediaKind.AUDIO) {
					throw new IllegalStateException("Only an audio sender sends tones");
				}
				if (dtmf == null) {
					dtmf = sender.getDtmfSender();

					if (dtmf == null) {
						throw new IllegalStateException("The sender cannot send tones");
					}

					dtmf.registerObserver((tone, buffer) -> toneChanged(tone));
				}
				if (!dtmf.canInsertDtmf()) {
					throw new IllegalStateException("The sender cannot send tones now. It needs a track, "
							+ "and the remote peer must have accepted telephone events");
				}
				if (!dtmf.insertDtmf(tones, (int) durationMs, (int) gapMs)) {
					throw new IllegalStateException("WebRTC refused the tones \"" + tones + "\"");
				}

				if (pendingTones != null) {
					pendingTones.completeExceptionally(new CancellationException("Replaced by later tones"));
				}

				if (hasTone(tones)) {
					pendingTones = sent;
				}
				else {
					// Nothing to play, so no event would complete the future.
					pendingTones = null;
					sent.complete(null);
				}
			}
			catch (Throwable e) {
				sent.completeExceptionally(e instanceof IllegalStateException
						? e : PeerImpl.asWebRtcException(e, "Sending tones failed"));
			}
		});

		return peer.toPublic(sent);
	}

	@Override
	public Subscription onTone(Consumer<String> handler) {
		requireNonNull(handler, "handler");

		return peer.subscribe(() -> toneHandlers.add(handler));
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

		if (dtmf != null) {
			dtmf.unregisterObserver();
			dtmf = null;
		}
		if (pendingTones != null) {
			pendingTones.completeExceptionally(peer.isClosed()
					? peer.closedError() : new IllegalStateException("The sender was removed"));
			pendingTones = null;
		}

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

	/** Called on a WebRTC thread, so it only posts. */
	private void toneChanged(String tone) {
		String current = tone == null ? "" : tone;

		peer.control.execute(() -> {
			// The handlers hear of the tone before the future of the tones
			// completes, which also goes through the event executor.
			peer.events.execute(() -> toneHandlers.accept(current));

			if (current.isEmpty() && pendingTones != null) {
				pendingTones.complete(null);
				pendingTones = null;
			}
		});
	}

	private void checkUsable() {
		if (peer.isClosed()) {
			throw peer.closedError();
		}
		if (attachError != null) {
			throw PeerImpl.asWebRtcException(attachError, "The track was never added");
		}
		if (removed) {
			throw new IllegalStateException("The sender was removed");
		}
	}

	private static boolean hasTone(String tones) {
		for (int i = 0; i < tones.length(); i++) {
			if ("0123456789ABCDabcd#*,".indexOf(tones.charAt(i)) >= 0) {
				return true;
			}
		}

		return false;
	}

	private CompletableFuture<Void> run(Runnable operation) {
		CompletableFuture<Void> result = new CompletableFuture<>();

		peer.control.execute(() -> {
			try {
				checkUsable();
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
