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
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCEncodedFrameTransformer;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.media.MediaStream;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.video.VideoTrack;

/**
 * The tracks a peer receives.
 * <p>
 * WebRTC's reports of them are handled on the control executor, and what the
 * handlers have been told so far is confined to the event executor, as the
 * methods say. Any thread may ask for the current tracks. A handler added late
 * hears of every track still there.
 */
final class RemoteTracks {

	private final PeerImpl peer;

	/**
	 * Makes the transform of each track, set before the application hears of
	 * it, from what asks the track's sender for a key frame; or null.
	 */
	private final Function<Runnable, RTCEncodedFrameTransformer> transforms;

	private final BiConsumer<RTCRtpReceiver, RTCEncodedFrameTransformer> installTransform;

	private final CopyOnWriteArrayList<RemoteTrackImpl> tracks = new CopyOnWriteArrayList<>();

	// Confined to the control executor.

	private final Map<String, RemoteTrackImpl> byId = new HashMap<>();

	/**
	 * The kinds of media whose tracks this peer drops, those it declined when
	 * it answered an offer that replaced its own.
	 */
	private final Set<MediaKind> declined = EnumSet.noneOf(MediaKind.class);

	// Confined to the event executor.

	private final List<RemoteTrack> delivered = new ArrayList<>();

	private final Handlers<RemoteTrack> handlers;


	RemoteTracks(PeerImpl peer, Function<Runnable, RTCEncodedFrameTransformer> transforms) {
		this(peer, transforms, RTCRtpReceiver::setTransform);
	}

	/** Allows a test to fail installation before the native receiver takes the transform. */
	RemoteTracks(PeerImpl peer, RTCEncodedFrameTransformer transform,
			BiConsumer<RTCRtpReceiver, RTCEncodedFrameTransformer> installTransform) {
		this(peer, transform == null ? null : keyFrameRequest -> transform, installTransform);
	}

	private RemoteTracks(PeerImpl peer, Function<Runnable, RTCEncodedFrameTransformer> transforms,
			BiConsumer<RTCRtpReceiver, RTCEncodedFrameTransformer> installTransform) {
		this.peer = peer;
		this.installTransform = installTransform;
		this.transforms = transforms;
		this.handlers = new Handlers<>(peer.rtc::reportError);
	}

	/**
	 * @return the tracks received now.
	 */
	List<RemoteTrack> current() {
		return Collections.unmodifiableList(new ArrayList<RemoteTrack>(tracks));
	}

	/** Snapshot only existing application sinks; diagnostics never enables PCM delivery. */
	Map<String, long[]> pcmObservations() {
		Map<String, long[]> result = new HashMap<>();
		for (RemoteTrackImpl track : tracks) {
			if (track instanceof RemoteAudioImpl) {
				long[] observation = ((RemoteAudioImpl) track).pcmObservation();
				if (observation != null) {
					result.put(track.id(), observation);
				}
			}
		}
		return result;
	}

	Subscription onTrack(Consumer<RemoteTrack> handler) {
		requireNonNull(handler, "handler");

		return peer.subscribe(() -> {
			for (RemoteTrack track : new ArrayList<>(delivered)) {
				peer.call(handler, track);
			}
			return handlers.add(handler);
		});
	}

	/**
	 * Drops the tracks of a kind from now on. On the control executor.
	 */
	void decline(MediaKind kind) {
		declined.add(kind);
	}

	/**
	 * Takes a track WebRTC reported. On the control executor.
	 */
	void added(RTCRtpReceiver receiver, MediaStream[] streams) {
		List<String> streamIds = new ArrayList<>();

		for (MediaStream stream : streams) {
			streamIds.add(stream.id());
			stream.dispose();
		}

		if (peer.isClosed()) {
			receiver.dispose();
			return;
		}

		MediaStreamTrack track = receiver.getTrack();
		String id = track.getId();

		if (declined.contains(track instanceof VideoTrack ? MediaKind.VIDEO : MediaKind.AUDIO)) {
			// Of an m-line this peer rejected, when it answered an offer that
			// replaced its own.
			track.dispose();
			receiver.dispose();
			return;
		}
		if (byId.containsKey(id)) {
			// Renegotiation reported a track that is already known.
			track.dispose();
			receiver.dispose();
			return;
		}

		// The track a key frame request of the transform goes to, once there
		// is one: frames may arrive before it is published.
		AtomicReference<RemoteTrackImpl> published = new AtomicReference<>();

		if (transforms != null) {
			String owner = "the track " + id + " of " + peer.name;

			try {
				RTCEncodedFrameTransformer transform = transforms.apply(() -> {
					RemoteTrackImpl remote = published.get();

					if (remote instanceof RemoteVideoImpl) {
						((RemoteVideoImpl) remote).requestKeyFrame();
					}
				});

				// Before the application hears of the track, and so before
				// its first frame arrives.
				installTransform.accept(receiver, peer.guard(transform, owner));
			}
			catch (Throwable e) {
				try {
					stopReceiver(receiver);
				}
				catch (Throwable stopError) {
					e.addSuppressed(stopError);
					// A path that could not be stopped must not keep receiving.
					peer.close();
					peer.connection().close();
				}
				finally {
					try {
						track.dispose();
					}
					finally {
						receiver.dispose();
					}
				}
				peer.deliverError(PeerImpl.asWebRtcException(e, "Setting the receive transform of " + owner
						+ " failed"));
				return;
			}
		}

		RemoteTrackImpl remote = track instanceof VideoTrack
				? new RemoteVideoImpl(peer, receiver, (VideoTrack) track, id, streamIds)
				: new RemoteAudioImpl(peer, receiver, (AudioTrack) track, id, streamIds);

		byId.put(id, remote);
		tracks.add(remote);
		published.set(remote);

		peer.events.execute(() -> {
			delivered.add(remote);
			handlers.accept(remote);
		});
	}

	/** Stops the native receive path, rather than only releasing its Java view. */
	private void stopReceiver(RTCRtpReceiver receiver) {
		RTCRtpTransceiver[] transceivers = peer.connection().getTransceivers();

		try {
			for (RTCRtpTransceiver transceiver : transceivers) {
				RTCRtpReceiver candidate = transceiver.getReceiver();

				try {
					if (receiver.equals(candidate)) {
						transceiver.stopImmediately();
						return;
					}
				}
				finally {
					candidate.dispose();
				}
			}
		}
		finally {
			for (RTCRtpTransceiver transceiver : transceivers) {
				transceiver.dispose();
			}
		}
		throw new IllegalStateException("The receiver has no transceiver to stop");
	}

	/**
	 * Ends a track WebRTC took away. On the control executor.
	 */
	void removed(RTCRtpReceiver receiver) {
		MediaStreamTrack track = receiver.getTrack();
		String id = track.getId();

		track.dispose();
		receiver.dispose();

		RemoteTrackImpl remote = byId.remove(id);

		if (remote == null || peer.isClosed()) {
			return;
		}

		tracks.remove(remote);
		remote.end();

		peer.events.execute(() -> {
			delivered.remove(remote);
			remote.fireEnded();
		});
	}

	/**
	 * Ends every track, as the peer does when it closes. On the control
	 * executor.
	 *
	 * @return the tracks ended, for {@link #fireEnded}.
	 */
	List<RemoteTrackImpl> endAll() {
		List<RemoteTrackImpl> ended = new ArrayList<>(tracks);

		for (RemoteTrackImpl remote : ended) {
			remote.end();
		}
		tracks.clear();
		byId.clear();

		return ended;
	}

	/**
	 * Tells the handlers of the given tracks they ended, and forgets what the
	 * handlers heard of. On the event executor.
	 */
	void fireEnded(List<RemoteTrackImpl> ended) {
		for (RemoteTrackImpl remote : ended) {
			remote.fireEnded();
		}
		delivered.clear();
	}

	/**
	 * Lets go of the handlers. On the event executor.
	 */
	void clearHandlers() {
		handlers.clear();
	}
}
