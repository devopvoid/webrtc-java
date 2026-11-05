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
 * Shows what the decode thread of a player is up to, which the interface of a
 * player does not: a seek moves the position at once, and a thread held up by
 * a full queue looks like one that has nothing to do. It lives in the tests so
 * that nothing else can reach it.
 * <p>
 * The counts are those of all players in the process.
 *
 * @author Alex Andres
 */
final class PlaybackProbe {

	static {
		FFmpeg.load();
	}


	private PlaybackProbe() {
		// Static access only.
	}

	/**
	 * Returns how many seeks decode threads have carried out so far.
	 */
	static native long seeks();

	/**
	 * Returns how many decode threads are waiting for room in a queue.
	 */
	static native int waitingPushes();

}
