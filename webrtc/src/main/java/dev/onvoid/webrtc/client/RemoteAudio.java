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

/**
 * An audio track the remote peer sends.
 */
public interface RemoteAudio extends RemoteTrack {

	/**
	 * Calls the handler with the decoded audio, in order. Audio reaches
	 * handlers only while the runtime plays out, which a {@link
	 * AudioMode#HEADLESS} runtime always does.
	 *
	 * @param handler called on a thread of this subscription.
	 *
	 * @return the subscription that stops the audio.
	 */
	Subscription onAudio(Consumer<AudioChunk> handler);

	/**
	 * Sets the playout gain of this track.
	 *
	 * @param volume 0 for silence to 10, where 1 plays the track as received.
	 */
	void setVolume(double volume);

}
