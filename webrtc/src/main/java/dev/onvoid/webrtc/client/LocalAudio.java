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

import java.util.function.Consumer;

import dev.onvoid.webrtc.media.audio.VoiceActivityConfig;

/**
 * An audio track this side sends.
 */
public interface LocalAudio extends LocalTrack {

	/**
	 * Like {@link #onVoiceActivity(VoiceActivityConfig, Consumer)}, with the
	 * default threshold and hangover of {@link VoiceActivityConfig}.
	 *
	 * @param handler called on the runtime's event executor, in order.
	 *
	 * @return the subscription that stops the calls.
	 */
	Subscription onVoiceActivity(Consumer<Boolean> handler);

	/**
	 * Calls the handler when this side begins to speak, with {@code true}, and
	 * when it stops, with {@code false}. The audio is analyzed in native code,
	 * and only these changes reach Java. Pushed audio is analyzed as pushed. A
	 * microphone's audio is analyzed after audio processing, with echo
	 * cancellation, noise suppression and gain control applied as configured,
	 * so that the remote peer's voice from the speakers does not count as
	 * this side speaking. It flows while WebRTC records, which it does while a
	 * peer sends a microphone track, and every microphone track of a runtime
	 * hears the same, as WebRTC records one microphone for all of them.
	 * <p>
	 * Closing the subscription or the track while speech goes on calls the
	 * handler no more, so it does not hear the end of that speech.
	 *
	 * @param config  when audio counts as speech.
	 * @param handler called on the runtime's event executor, in order.
	 *
	 * @return the subscription that stops the calls.
	 */
	Subscription onVoiceActivity(VoiceActivityConfig config, Consumer<Boolean> handler);

}
