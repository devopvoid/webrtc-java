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

import dev.onvoid.webrtc.internal.NativeLoader;
import dev.onvoid.webrtc.internal.NativeObject;

/**
 * Synchronized Clock for A/V timing. Provides timing functionality for
 * audio and video synchronization.
 *
 * @author Alex Andres
 */
public class SyncClock extends NativeObject {

    static {
        try {
            NativeLoader.loadLibrary("webrtc-java");
        }
        catch (Exception e) {
            throw new RuntimeException("Load library 'webrtc-java' failed", e);
        }
    }


    /**
     * Constructs a new SyncClock instance.
     */
    public SyncClock() {
        super();

        initialize();
    }

    /**
     * Returns the current time of the media clock, in microseconds.
     * <p>
     * This is the clock in which capture timestamps are interpreted, for
     * example by {@link dev.onvoid.webrtc.media.video.CustomVideoSource#pushFrame(
     * dev.onvoid.webrtc.media.video.VideoFrame, long) pushFrame} and
     * {@link dev.onvoid.webrtc.media.audio.CustomAudioSource#pushAudio(byte[],
     * int, int, int, int, long) pushAudio}. It is the monotonic clock WebRTC
     * itself runs on, which is what makes those timestamps comparable with
     * WebRTC's own notion of now.
     * <p>
     * It is unrelated to the timestamps an <em>instance</em> of this class
     * reports: {@link #getTimestampUs()} counts from the moment that instance
     * was created, while this clock counts from an arbitrary but process-wide
     * fixed point.
     *
     * @return The current time of the media clock in microseconds.
     */
    public static native long currentTimeUs();

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