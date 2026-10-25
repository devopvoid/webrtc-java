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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.internal.Timeouts;
import dev.onvoid.webrtc.media.audio.AudioTrack;

/**
 * A remote audio track. Each audio subscription is a sink of the native track
 * with a thread of its own.
 */
final class RemoteAudioImpl extends RemoteTrackImpl implements RemoteAudio {

	private final AudioTrack track;

	// Confined to the peer's control executor.

	private final List<AudioDispatcher> dispatchers = new ArrayList<>();


	RemoteAudioImpl(PeerImpl peer, RTCRtpReceiver receiver, AudioTrack track,
			String id, List<String> streamIds) {
		super(peer, receiver, track, MediaKind.AUDIO, id, streamIds);

		this.track = track;
	}

	@Override
	public RemoteAudio asAudio() {
		return this;
	}

	@Override
	public Subscription onAudio(Consumer<AudioChunk> handler) {
		return onAudio(peer.rtc.audioBuffer, handler);
	}

	@Override
	public Subscription onAudio(Duration buffer, Consumer<AudioChunk> handler) {
		Timeouts.requirePositive(buffer, "buffer");
		requireNonNull(handler, "handler");

		SerialExecutor executor = new SerialExecutor(peer.rtc.framePool, peer.rtc::reportError, false, null);
		AudioDispatcher dispatcher = new AudioDispatcher(handler, buffer, executor, peer.rtc.audioWork,
				peer.rtc::reportError);

		onControl(() -> {
			dispatchers.add(dispatcher);
			track.addSink(dispatcher);
		});

		return () -> {
			dispatcher.close();

			onControl(() -> {
				if (dispatchers.remove(dispatcher)) {
					track.removeSink(dispatcher);
				}
			});
		};
	}

	@Override
	public void setVolume(double volume) {
		if (volume < 0 || volume > 10) {
			throw new IllegalArgumentException("The volume must be between 0 and 10, got " + volume);
		}

		onControl(() -> track.setVolume(volume));
	}

	@Override
	void detachSinks() {
		for (AudioDispatcher dispatcher : dispatchers) {
			track.removeSink(dispatcher);
			dispatcher.close();
		}

		dispatchers.clear();
	}
}
