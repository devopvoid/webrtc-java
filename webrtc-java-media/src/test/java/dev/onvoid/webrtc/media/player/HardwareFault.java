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
 * Makes the hardware video decoder of the native library fail on purpose, so
 * that the tests can see what a player does when a GPU fails, which no GPU
 * does on demand. It lives in the tests so that nothing else can reach it.
 * <p>
 * A fault is armed for the next hardware decoder that runs into it, fires
 * once, and has to be {@link #disarm() disarmed} when a test is done.
 *
 * @author Alex Andres
 */
final class HardwareFault {

	static {
		FFmpeg.load();
	}


	private HardwareFault() {
		// Static access only.
	}

	/**
	 * Fails the send of the packet that follows the given number of them to
	 * the hardware decoder.
	 *
	 * @param invalidData  True to fail as bad data does, which says nothing
	 *                     of the hardware, false to fail as the hardware does.
	 * @param afterPackets How many packets are sent before the one that fails.
	 */
	static native void failSend(boolean invalidData, int afterPackets);

	/**
	 * Keeps the hardware decoder from delivering any picture, and fails it
	 * when it is asked for them at the end of the stream.
	 */
	static native void failDrain();

	/**
	 * Forgets any fault, and the thread count of {@link #softwareThreads()}.
	 */
	static native void disarm();

	/**
	 * Returns the number of threads the last software video decoder to be
	 * opened got, or 0 if there was none since the last {@link #disarm()}.
	 */
	static native int softwareThreads();

}
