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
 * Makes an allocation of the native library fail on purpose, so that the
 * tests can see what a native entry point does when the C++ heap is
 * exhausted, which it is not on demand. It lives in the tests so that nothing
 * else can reach it.
 *
 * @author Alex Andres
 */
public final class AllocationFault {

	static {
		FFmpeg.load();
	}


	private AllocationFault() {
		// Static access only.
	}

	/**
	 * Fails the next copy of a Java string that a native entry point makes,
	 * such as the source of a reader or the file of a recorder.
	 */
	public static native void failNextStringCopy();

	/**
	 * Forgets a fault that has not fired.
	 */
	public static native void disarm();

	/**
	 * Returns how many Java strings the native library holds the characters
	 * of right now, which is 0 whenever no entry point is running.
	 */
	public static native int heldStrings();

}
