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
 * A track this side sends, made by {@link WebRtc#media()}. One track may be sent
 * on any number of peers.
 */
public interface LocalTrack extends AutoCloseable {

	/**
	 * @return the track id.
	 */
	String id();

	/**
	 * @return audio or video.
	 */
	MediaKind kind();

	/**
	 * @return false while the track sends black frames or silence.
	 */
	boolean isEnabled();

	/**
	 * Mutes or unmutes the track. A muted track sends black frames or silence,
	 * while its capture stays open.
	 *
	 * @param enabled false to mute.
	 */
	void setEnabled(boolean enabled);

	/**
	 * Calls the handler once the track ended. A handler added after that is
	 * called at once.
	 *
	 * @param handler called on a background thread.
	 *
	 * @return the subscription that removes the handler.
	 */
	Subscription onEnded(Runnable handler);

	/**
	 * @return true once the track is closed.
	 */
	boolean isEnded();

	/**
	 * Stops sending the track on every peer, without renegotiating, and stops
	 * its capture. Never blocks, may be called more than once.
	 */
	@Override
	void close();

}
