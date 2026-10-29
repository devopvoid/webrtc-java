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

import dev.onvoid.webrtc.internal.Timeouts;
import dev.onvoid.webrtc.media.audio.AudioTrack;

/**
 * A local audio track, from the microphone or pushed audio. Each audio
 * subscription has a thread of its own. Pushed audio reaches it as a sink of
 * the native track. A microphone's source hands no audio to sinks, as WebRTC
 * passes captured audio straight to the senders, so a microphone track takes
 * it from the runtime's audio processing instead.
 */
class LocalAudioImpl extends LocalTrackImpl implements LocalAudio {

	private final boolean microphone;

	// Guarded by this.

	private final List<AudioDispatcher> dispatchers = new ArrayList<>();


	/**
	 * @param microphone true for a track of captured audio, false for one
	 *                   whose source hands its audio to sinks.
	 */
	LocalAudioImpl(LocalMediaImpl media, AudioTrack track, Runnable releaseSource, boolean microphone) {
		super(media, track, MediaKind.AUDIO, releaseSource);

		this.microphone = microphone;
	}

	@Override
	public Subscription onAudio(Consumer<AudioChunk> handler) {
		return onAudio(rtc.audioBuffer, handler);
	}

	@Override
	public Subscription onAudio(Duration buffer, Consumer<AudioChunk> handler) {
		Timeouts.requirePositive(buffer, "buffer");
		requireNonNull(handler, "handler");

		SerialExecutor executor = new SerialExecutor(rtc.framePool, rtc::reportError, false, null);
		AudioDispatcher dispatcher = new AudioDispatcher(handler, buffer, executor, rtc.audioWork,
				rtc::reportError);

		synchronized (this) {
			if (!isLive()) {
				return () -> { };
			}

			attach(dispatcher);
			dispatchers.add(dispatcher);
		}

		return () -> {
			dispatcher.close();

			synchronized (LocalAudioImpl.this) {
				if (dispatchers.remove(dispatcher) && isLive()) {
					detach(dispatcher);
				}
			}
		};
	}

	@Override
	void beforeRelease() {
		List<AudioDispatcher> attached;

		synchronized (this) {
			attached = new ArrayList<>(dispatchers);
			dispatchers.clear();
		}

		for (AudioDispatcher dispatcher : attached) {
			detach(dispatcher);
			dispatcher.close();
		}
	}

	private void attach(AudioDispatcher dispatcher) {
		if (microphone) {
			rtc.capturedAudio.add(dispatcher);
		}
		else {
			audioTrack().addSink(dispatcher);
		}
	}

	private void detach(AudioDispatcher dispatcher) {
		if (microphone) {
			rtc.capturedAudio.remove(dispatcher);
		}
		else {
			audioTrack().removeSink(dispatcher);
		}
	}

	private AudioTrack audioTrack() {
		return (AudioTrack) nativeTrack();
	}
}
