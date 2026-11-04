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
	 * Fails the next other native allocation that is checked, such as that of
	 * a recorder track, or the copy of a frame to record.
	 */
	public static native void failNextAllocation();

	/**
	 * Fails the next native thread start that is checked, such as that of a
	 * player's decoder or a recorder's writer, as one fails that the system
	 * has no memory for.
	 */
	public static native void failNextThreadStart();

	/**
	 * Fails an allocation of a recorder's writer thread, as one fails that
	 * holds back or describes a frame, once that thread has handled the given
	 * number of frames. Unlike the other faults, it throws on a thread of
	 * the native library's own, where no entry point turns the C++ exception
	 * into a Java one.
	 *
	 * @param afterFrames How many frames the writer handles before the one
	 *                    whose allocation fails.
	 */
	public static native void failWriterAllocation(int afterFrames);

	/**
	 * Fails the next setting of an FFmpeg option that is checked, such as the
	 * protocols a reader may reach.
	 */
	public static native void failNextOption();

	/**
	 * Makes a recorder fail to attach to a track, as it does when {@code
	 * encoded_observer_add()} returns null.
	 *
	 * @param afterTracks How many tracks it attaches to before the one that
	 *                    fails.
	 */
	public static native void failObserverAdd(int afterTracks);

	/**
	 * Makes a recorder's queue look full for the next frame, which no test can
	 * fill for real without holding up the writer.
	 */
	public static native void failNextQueueRoom();

	/**
	 * @return True if a fault is armed that has not fired yet.
	 */
	public static native boolean isArmed();

	/**
	 * Throws a C++ exception in native code that holds the given string, for
	 * the entry point to turn into a Java exception.
	 *
	 * @param kind    0 for std::bad_alloc, 1 for a std::runtime_error with the
	 *                message, 2 for the same after a JNI call that failed,
	 *                anything else for one that is no std::exception.
	 * @param message The message.
	 */
	static native void throwInNative(int kind, String message);

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
