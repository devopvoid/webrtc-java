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

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.VoiceActivityConfig;
import dev.onvoid.webrtc.media.audio.VoiceActivityListener;

/**
 * A local audio track, from the microphone or pushed audio. Speech is
 * detected in native code. Pushed audio reaches the detector as a sink of the
 * native track. A microphone's source hands no audio to sinks, as WebRTC
 * passes captured audio straight to the senders, so a microphone track takes
 * its speech from the runtime's audio processing instead.
 */
class LocalAudioImpl extends LocalTrackImpl implements LocalAudio {

	private final boolean microphone;

	// Guarded by this.

	private final List<VoiceActivity> listeners = new ArrayList<>();


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

	@Override
	void beforeRelease() {
		List<VoiceActivity> attached;

		synchronized (this) {
			attached = new ArrayList<>(listeners);
			listeners.clear();
		}

		for (VoiceActivity listener : attached) {
			listener.closed = true;
			detach(listener);
		}
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
