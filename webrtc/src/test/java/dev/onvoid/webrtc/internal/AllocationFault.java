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

package dev.onvoid.webrtc.internal;

/**
 * Makes an allocation of the native library fail on purpose, so that the tests
 * can see what the code around it does when memory runs out, which it does not
 * on demand. It lives in the tests so that nothing else can reach it.
 * <p>
 * The library has to be loaded, as it is by any test that created a factory.
 *
 * @author Alex Andres
 */
public final class AllocationFault {

	private AllocationFault() {
		// Static access only.
	}

	/**
	 * Fails the next registration of a track listener, right after the track
	 * got its observer and before anything owns that observer.
	 */
	public static native void failNextListener();

	/**
	 * Fails the next {@code encoded_observer_add()} of the extension API, as
	 * an allocation for the handle it returns does.
	 */
	public static native void failNextEncodedObserver();

	/**
	 * Calls {@code encoded_observer_add()} of the extension API, with an
	 * observer that ignores its frames.
	 *
	 * @param frames A handle from {@link NativeApi#encodedFramesOf}.
	 *
	 * @return The observer, or {@code 0} if it could not be attached.
	 */
	static native long addEncodedObserver(long frames);

	/**
	 * Calls {@code encoded_observer_remove()} of the extension API.
	 *
	 * @param observer The observer from {@link #addEncodedObserver(long)}.
	 */
	static native void removeEncodedObserver(long observer);

	/**
	 * @param frames A handle from {@link NativeApi#encodedFramesOf}.
	 *
	 * @return How many observers are attached to what the handle stands for.
	 */
	static native int encodedObservers(long frames);

	/**
	 * Forgets a fault that has not fired.
	 */
	public static native void disarm();

}
