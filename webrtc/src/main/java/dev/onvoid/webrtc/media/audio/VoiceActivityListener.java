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

package dev.onvoid.webrtc.media.audio;

/**
 * Hears when speech begins and ends in an audio stream, as a native voice
 * activity detector finds it with a {@link VoiceActivityConfig}. The audio
 * stays in native code; only the changes reach Java.
 *
 * @see dev.onvoid.webrtc.PeerConnectionFactory#addVoiceActivityListener
 * @see AudioTrack#addVoiceActivityListener
 */
@FunctionalInterface
public interface VoiceActivityListener {

	/**
	 * Called when speech begins, with {@code true}, and when it ends, with
	 * {@code false}, alternating and starting with {@code true}. It runs on
	 * the thread that delivers the audio, such as WebRTC's capture thread, so
	 * it must return promptly, and it must not add or remove a listener.
	 *
	 * @param speaking whether speech began or ended.
	 */
	void onVoiceActivity(boolean speaking);

}
