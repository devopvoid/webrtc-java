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

package dev.onvoid.webrtc.media;

import dev.onvoid.webrtc.internal.NativeObject;

/**
 * Synchronized Clock for A/V timing. Provides timing functionality for
 * audio and video synchronization.
 *
 * @author Alex Andres
 */
public class SyncClock extends NativeObject {

    /**
     * Constructs a new SyncClock instance.
     */
    public SyncClock() {
        super();

        initialize();
    }

    /**
     * Get the current timestamp in microseconds.
     *
     * @return The current timestamp in microseconds.
     */
    public native long getTimestampUs();

    /**
     * Get the current timestamp in milliseconds.
     *
     * @return The current timestamp in milliseconds.
     */
    public native long getTimestampMs();

    /**
     * Get NTP timestamp for RTP synchronization.
     *
     * @return The NTP timestamp.
     */
    public native long getNtpTime();

    /**
     * Disposes of any native resources held by this clock.
     * This method should be called when the clock is no longer needed
     * to prevent memory leaks.
     */
    public native void dispose();

    /**
     * Initializes the native resources required by this clock.
     */
    private native void initialize();

}