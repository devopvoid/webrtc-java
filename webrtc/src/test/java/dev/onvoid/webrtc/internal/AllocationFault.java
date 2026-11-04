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
	 * Forgets a fault that has not fired.
	 */
	public static native void disarm();

}
