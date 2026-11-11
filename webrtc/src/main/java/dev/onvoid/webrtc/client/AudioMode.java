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

/**
 * Where the audio a runtime sends can come from, and where it plays the audio
 * it receives. The choice is made when the runtime is built.
 */
public enum AudioMode {

	/**
	 * Capture the microphone and play to the speakers. Audio pushed through
	 * {@link CustomAudio} can be sent as well, beside the microphone, also on
	 * one peer.
	 */
	DEVICES,

	/**
	 * Send audio pushed through {@link CustomAudio} and play remote audio to the
	 * speakers. The microphone is never opened.
	 */
	CUSTOM,

	/**
	 * Use no audio hardware at all. Send audio pushed through {@link CustomAudio}
	 * and read remote audio through {@link RemoteAudio#onAudio}. Suits servers and
	 * bots.
	 */
	HEADLESS

}
