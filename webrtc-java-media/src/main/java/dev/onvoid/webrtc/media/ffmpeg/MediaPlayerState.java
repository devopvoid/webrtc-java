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

package dev.onvoid.webrtc.media.ffmpeg;

/**
 * What a {@link MediaPlayer} is currently doing.
 *
 * @author Alex Andres
 */
public enum MediaPlayerState {

	/** Ready to play, but not playing. */
	IDLE,

	/** Media is being decoded and delivered in real time. */
	PLAYING,

	/**
	 * Playback is held where it is. Resuming carries on from there rather
	 * than delivering everything that fell due in the meantime.
	 */
	PAUSED,

	/**
	 * The source ran out and everything decoded from it has been delivered.
	 * Playing again starts it over. A looping player never reaches this.
	 */
	ENDED,

	/** The player has been closed and cannot be used again. */
	CLOSED;


	/** Cached, because values() hands out a fresh array on every call. */
	private static final MediaPlayerState[] VALUES = values();


	/**
	 * Returns the state native code reported. The order of the constants above
	 * is the order the native {@code MediaPlayerState} enum uses, so the two
	 * have to be changed together.
	 *
	 * @param value The native state value.
	 *
	 * @return The matching state, or {@link #CLOSED} for a value this version
	 *         does not know.
	 */
	static MediaPlayerState of(int value) {
		return value >= 0 && value < VALUES.length ? VALUES[value] : CLOSED;
	}

}
