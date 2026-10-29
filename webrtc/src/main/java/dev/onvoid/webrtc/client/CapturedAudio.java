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
import java.util.concurrent.CopyOnWriteArrayList;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;

/**
 * Hands the microphone audio of a runtime, after audio processing, to the
 * audio subscriptions of its microphone tracks. The factory's audio
 * processing calls it on WebRTC's capture thread, and only while one is
 * subscribed, so that the capture thread does not call into Java for nothing.
 */
final class CapturedAudio implements AudioTrackSink {

	private final PeerConnectionFactory factory;

	/** Changed under this object's lock, read on the capture thread. */
	private final List<AudioTrackSink> sinks = new CopyOnWriteArrayList<>();


	CapturedAudio(PeerConnectionFactory factory) {
		this.factory = factory;
	}

	synchronized void add(AudioTrackSink sink) {
		if (sinks.isEmpty()) {
			factory.setCapturedAudioSink(this);
		}

		sinks.add(sink);
	}

	synchronized void remove(AudioTrackSink sink) {
		if (sinks.remove(sink) && sinks.isEmpty()) {
			factory.setCapturedAudioSink(null);
		}
	}

	@Override
	public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
		// Each sink copies what it keeps.
		for (AudioTrackSink sink : sinks) {
			sink.onData(data, bitsPerSample, sampleRate, channels, frames);
		}
	}
}
