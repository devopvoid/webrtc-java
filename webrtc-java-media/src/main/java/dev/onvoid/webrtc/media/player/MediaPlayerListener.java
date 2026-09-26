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

package dev.onvoid.webrtc.media.player;

/**
 * What a {@link MediaPlayer} reports while it runs.
 * <p>
 * Calls arrive on the player's own thread, not on the thread that asked for
 * playback, and that thread is the one decoding the media. An implementation
 * must therefore return promptly and must not call back into the player in a
 * way that waits for it.
 * <p>
 * Closing the player from here, or the {@link MediaFileSource} it belongs to,
 * is fine: the native player is then released once this call has returned,
 * rather than during it.
 *
 * @author Alex Andres
 */
public interface MediaPlayerListener {

	/**
	 * The player moved to another state.
	 *
	 * @param state The state it moved to.
	 */
	default void onStateChanged(MediaPlayerState state) {
	}

	/**
	 * The source ran out and everything decoded from it has been delivered. A
	 * looping player never reports this, since it starts over instead.
	 */
	default void onEndOfStream() {
	}

	/**
	 * Playback stopped because something went wrong. The player is left
	 * {@link MediaPlayerState#PAUSED} where it failed: playing again carries
	 * on past what failed, and a seek moves away from it, though a source
	 * that cannot be read at all fails again.
	 *
	 * @param message What went wrong, as FFmpeg described it.
	 */
	default void onError(String message) {
	}

}
