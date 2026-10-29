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

import java.time.Duration;
import java.util.function.Consumer;

/**
 * An audio track this side sends.
 */
public interface LocalAudio extends LocalTrack {

	/**
	 * Calls the handler with the audio this track sends, in order, such as
	 * for a voice activity detector or a level meter. Pushed audio arrives as
	 * pushed. A microphone's audio arrives after audio processing, with echo
	 * cancellation, noise suppression and gain control applied as configured,
	 * so that the remote peer's voice from the speakers does not count as
	 * this side speaking. It flows while WebRTC records, which it does while
	 * a peer sends a microphone track, and every microphone track of a
	 * runtime has the same, as WebRTC records one microphone for all of them.
	 * <p>
	 * A handler that falls behind gets the audio of the last {@link
	 * WebRtc.Builder#audioBuffer(Duration) audio buffer} of the runtime; the
	 * oldest audio beyond that is dropped.
	 *
	 * @param handler called on a thread of this subscription.
	 *
	 * @return the subscription that stops the audio.
	 */
	Subscription onAudio(Consumer<AudioChunk> handler);

	/**
	 * Like {@link #onAudio(Consumer)}, with a buffer of its own: a handler
	 * that falls behind gets the audio of the last {@code buffer}, and the
	 * oldest audio beyond that is dropped. A buffer shorter than one chunk
	 * keeps only the newest chunk.
	 *
	 * @param buffer  the most audio that waits for the handler.
	 * @param handler called on a thread of this subscription.
	 *
	 * @return the subscription that stops the audio.
	 */
	Subscription onAudio(Duration buffer, Consumer<AudioChunk> handler);

}
