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

import dev.onvoid.webrtc.media.audio.AudioDataAccess;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.audio.VoiceActivityConfig;
import dev.onvoid.webrtc.media.audio.VoiceActivityListener;

/**
 * A local audio track, from the microphone or pushed audio. Speech is
 * detected in native code. Pushed audio reaches the detector as a sink of the
 * native track. A microphone track takes its speech from the detector of the
 * runtime's audio processing instead, which sees the processed microphone
 * audio once, whatever the number of microphone tracks.
 */
class LocalAudioImpl extends LocalTrackImpl implements LocalAudio {

	private final boolean microphone;

	// Guarded by this.

	private final List<VoiceActivity> listeners = new ArrayList<>();

	private final List<AudioTrackSink> sinks = new ArrayList<>();


	/**
	 * @param microphone true for a track of captured audio, false for one
	 *                   whose source hands its audio to sinks.
	 */
	LocalAudioImpl(LocalMediaImpl media, AudioTrack track, Runnable releaseSource, boolean microphone) {
		super(media, track, MediaKind.AUDIO, releaseSource);

		this.microphone = microphone;
	}

	@Override
	public Subscription onVoiceActivity(Consumer<Boolean> handler) {
		return onVoiceActivity(new VoiceActivityConfig(), handler);
	}

	@Override
	public Subscription onVoiceActivity(VoiceActivityConfig config, Consumer<Boolean> handler) {
		requireNonNull(config, "config");
		requireNonNull(handler, "handler");

		VoiceActivity listener = new VoiceActivity(handler);

		synchronized (this) {
			if (!isLive()) {
				return () -> { };
			}

			if (microphone) {
				rtc.factory.addVoiceActivityListener(config, listener);
			}
			else {
				audioTrack().addVoiceActivityListener(config, listener);
			}

			listeners.add(listener);
		}

		return () -> {
			listener.closed = true;

			synchronized (LocalAudioImpl.this) {
				// Once the track ended, the release removes what is left.
				if (isLive() && listeners.remove(listener)) {
					detach(listener);
				}
			}
		};
	}

	/**
	 * Hands the audio the track sends to the sink, on the thread that
	 * delivers it, in an array that is valid only during the call. A
	 * microphone's audio flows only while WebRTC records it.
	 *
	 * @return the subscription that removes the sink.
	 */
	Subscription onPcm(AudioTrackSink sink) {
		requireNonNull(sink, "sink");

		synchronized (this) {
			if (!isLive()) {
				return () -> { };
			}

			sinks.add(sink);
			audioTrack().addSink(sink, AudioDataAccess.REUSED);
		}

		return () -> {
			synchronized (LocalAudioImpl.this) {
				// Once the track ended, the release removes what is left.
				if (isLive() && sinks.remove(sink)) {
					audioTrack().removeSink(sink);
				}
			}
		};
	}

	@Override
	void beforeRelease() {
		List<VoiceActivity> attached;
		List<AudioTrackSink> attachedSinks;

		synchronized (this) {
			attached = new ArrayList<>(listeners);
			listeners.clear();
			attachedSinks = new ArrayList<>(sinks);
			sinks.clear();
		}

		for (VoiceActivity listener : attached) {
			listener.closed = true;
			detach(listener);
		}
		for (AudioTrackSink sink : attachedSinks) {
			audioTrack().removeSink(sink);
		}
	}

	/**
	 * @return true for a track of captured audio, which flows only while
	 * WebRTC records, and so only while a peer sends a microphone track.
	 */
	boolean isMicrophone() {
		return microphone;
	}

	private void detach(VoiceActivity listener) {
		if (microphone) {
			rtc.factory.removeVoiceActivityListener(listener);
		}
		else {
			audioTrack().removeVoiceActivityListener(listener);
		}
	}

	private AudioTrack audioTrack() {
		return (AudioTrack) nativeTrack();
	}



	/**
	 * Hands the changes, which arrive on the thread that delivers the audio,
	 * to the event executor, so that no handler holds up that thread.
	 */
	private final class VoiceActivity implements VoiceActivityListener {

		private final Consumer<Boolean> handler;

		volatile boolean closed;


		VoiceActivity(Consumer<Boolean> handler) {
			this.handler = handler;
		}

		@Override
		public void onVoiceActivity(boolean speaking) {
			if (closed) {
				return;
			}

			media.events.execute(() -> {
				if (!closed) {
					handler.accept(speaking);
				}
			});
		}
	}
}
