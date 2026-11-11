/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.media.video.desktop;

import dev.onvoid.webrtc.internal.NativeLoader;

/**
 * Power management assertions to wake the display and prevent it from going to sleep on user idle.
 * <p>
 * Each instance is one independent request. Enabling an instance repeatedly counts once, so a single
 * {@link #disableUserActivity()} ends its request. The system may idle to sleep again once the last
 * enabled instance of this process is disabled. Keep the instance that enabled the request, since
 * disabling another instance does not end it. All methods are thread-safe.
 *
 * @author Alex Andres
 */
public class PowerManagement {

	/** Guards the request state and serializes access to the process-wide native assertion. */
	private static final Object LOCK = new Object();

	/** The number of instances with an active request. */
	private static int activeRequests;

	/** Whether this instance has an active request. */
	private boolean active;


	static {
		try {
			NativeLoader.loadLibrary("webrtc-java");
		}
		catch (Exception e) {
			throw new RuntimeException("Load library 'webrtc-java' failed", e);
		}
	}

	/**
	 * Declares that the user is active on the system and prevents the sleep idle time-out. Has no further
	 * effect if this instance is already enabled.
	 */
	public void enableUserActivity() {
		synchronized (LOCK) {
			if (!active) {
				active = true;
				activeRequests++;
			}

			// No-op while the native assertion is held; otherwise retries one that could not be taken.
			enableUserActivityInternal();
		}
	}

	/**
	 * Ends the request of this instance. The system may idle to sleep normally once no other instance has
	 * an active request. Has no further effect if this instance is not enabled.
	 */
	public void disableUserActivity() {
		synchronized (LOCK) {
			if (active) {
				active = false;
				activeRequests--;
			}

			// No-op without a native assertion; otherwise also retries a release that failed.
			if (activeRequests == 0) {
				disableUserActivityInternal();
			}
		}
	}

	native void enableUserActivityInternal();

	native void disableUserActivityInternal();

}
