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

package dev.onvoid.webrtc.media.recorder;

import dev.onvoid.webrtc.media.player.FFmpeg;

/**
 * Makes the writing of a recording fail on purpose, so that the tests can see
 * what a recorder does when the disk fills up or the file cannot be closed,
 * which no disk does on demand. It lives in the tests so that nothing else
 * can reach it.
 * <p>
 * The call that fails still runs, and only its result is replaced: writing
 * fails as on a full disk, closing with an I/O error. A fault is armed for
 * the next recorder that runs into it, fires once, and has to be {@link
 * #disarm() disarmed} when a test is done.
 *
 * @author Alex Andres
 */
final class WriteFault {

	static {
		FFmpeg.load();
	}


	private WriteFault() {
		// Static access only.
	}

	/**
	 * Fails the writing of the file header, before any media is in the file.
	 */
	static native void failHeader();

	/**
	 * Fails the write of the packet that follows the given number of them.
	 *
	 * @param afterPackets How many packets are written before the one that
	 *                     fails.
	 */
	static native void failPacket(int afterPackets);

	/**
	 * Fails the writing of the trailer, which finishes the file.
	 */
	static native void failTrailer();

	/**
	 * Fails the closing of the file, the last thing a recorder does with it.
	 */
	static native void failClose();

	/**
	 * Forgets a fault that has not fired.
	 */
	static native void disarm();

}
