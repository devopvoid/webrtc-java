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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.client.internal.EncodedFrames;
import dev.onvoid.webrtc.internal.NativeApi;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.MediaStreamTrackMuteListener;

/**
 * A {@link RemoteTrack} of a {@link PeerImpl}. The native receiver and track
 * view are used on the peer's control executor; the track's handlers run on
 * the peer's event executor.
 */
abstract class RemoteTrackImpl implements RemoteTrack {

	final PeerImpl peer;

	private final RTCRtpReceiver receiver;

	private final MediaStreamTrack track;

	private final String id;

	private final MediaKind kind;

	private final List<String> streamIds;

	private final MediaStreamTrackMuteListener muteListener;

	private volatile boolean muted;

	private volatile boolean ended;

	// Confined to the peer's event executor.

	private boolean endDelivered;

	private final Handlers<Boolean> muteHandlers;

	private final Handlers<Void> endedHandlers;


	RemoteTrackImpl(PeerImpl peer, RTCRtpReceiver receiver, MediaStreamTrack track,
			MediaKind kind, String id, List<String> streamIds) {
		this.peer = peer;
		this.receiver = receiver;
		this.track = track;
		this.kind = kind;
		this.id = id;
		this.streamIds = Collections.unmodifiableList(streamIds);
		this.muteHandlers = new Handlers<>(peer.rtc::reportError);
		this.endedHandlers = new Handlers<>(peer.rtc::reportError);
		this.muteListener = (mutedTrack, isMuted) -> peer.events.execute(() -> muteChanged(isMuted));

		track.addTrackMuteListener(muteListener);
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public MediaKind kind() {
		return kind;
	}

	@Override
	public List<String> streamIds() {
		return streamIds;
	}

	@Override
	public RemoteVideo asVideo() {
		throw new IllegalStateException("The track " + id + " is an audio track");
	}

	@Override
	public RemoteAudio asAudio() {
		throw new IllegalStateException("The track " + id + " is a video track");
	}

	@Override
	public boolean isMuted() {
		return muted;
	}

	@Override
	public boolean isEnded() {
		return ended;
	}

	@Override
	public Subscription onMute(Consumer<Boolean> handler) {
		requireNonNull(handler, "handler");

		return peer.subscribe(() -> muteHandlers.add(handler));
	}

	@Override
	public Subscription onEnded(Runnable handler) {
		requireNonNull(handler, "handler");

		return peer.subscribe(() -> {
			if (endDelivered) {
				peer.call(ignored -> handler.run(), null);
				return () -> { };
			}

			return endedHandlers.add(ignored -> handler.run());
		});
	}

	@Override
	public CompletableFuture<Void> transform(RTCEncodedFrameTransformer transform) {
		CompletableFuture<Void> result = new CompletableFuture<>();

		peer.control.execute(() -> {
			try {
				if (peer.isClosed()) {
					throw peer.closedError();
				}
				if (ended) {
					throw new IllegalStateException("The track " + id + " ended");
				}

				receiver.setTransform(peer.guard(transform, "the track " + id + " of " + peer.name));
				result.complete(null);
			}
			catch (Throwable e) {
				result.completeExceptionally(e instanceof IllegalStateException
						? e : PeerImpl.asWebRtcException(e, "Setting the transform of the track " + id + " failed"));
			}
		}, () -> result.completeExceptionally(new PeerClosedException("The runtime is closed")));

		return peer.toPublic(result);
	}

	@Override
	public String toString() {
		return "RemoteTrack[" + kind + ", " + id + "]";
	}

	/**
	 * Runs a change of the native track on the peer's control executor, or
	 * does nothing once the track ended.
	 */
	void onControl(Runnable change) {
		peer.control.execute(() -> {
			if (!ended) {
				change.run();
			}
		});
	}

	/**
	 * Hands a native extension the encoded frames of this track's receiver,
	 * taken on the peer's control executor, where key frames are asked for
	 * too.
	 */
	CompletableFuture<EncodedFrames.Source> encodedFrames() {
		return peer.encodedFrames(() -> {
			if (ended) {
				throw new IllegalStateException("The track " + id + " ended");
			}

			long frames = NativeApi.encodedFramesOf(receiver);

			if (frames == 0) {
				throw new IllegalStateException("The track " + id + " ended");
			}

			return new EncodedFrames.Source(frames, () -> onControl(() -> {
				if (kind == MediaKind.VIDEO) {
					receiver.requestKeyFrame();
				}
			}));
		});
	}

	/** Detaches everything from the native track, on the control executor. */
	void end() {
		if (ended) {
			return;
		}

		ended = true;
		detachSinks();

		try {
			track.removeTrackMuteListener(muteListener);
			track.dispose();
			receiver.dispose();
		}
		catch (Throwable e) {
			peer.rtc.reportError(e);
		}
	}

	/** Called once, on the control executor, while the native track exists. */
	abstract void detachSinks();

	/** Tells the handlers the track ended, on the event executor. */
	void fireEnded() {
		if (endDelivered) {
			return;
		}

		endDelivered = true;
		endedHandlers.accept(null);
		endedHandlers.clear();
		muteHandlers.clear();
	}

	private void muteChanged(boolean isMuted) {
		if (endDelivered || isMuted == muted) {
			return;
		}

		muted = isMuted;
		muteHandlers.accept(isMuted);
	}
}
