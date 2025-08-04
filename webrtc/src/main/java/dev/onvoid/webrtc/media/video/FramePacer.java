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

package dev.onvoid.webrtc.media.video;

/**
 * A utility class that regulates the pace at which video frames are processed.
 * <p>
 * FramePacer ensures that frames are processed at a consistent rate by introducing
 * appropriate delays between frame processing operations. This helps maintain a
 * steady frame rate for video applications.
 * <p>
 * Usage example:
 * <pre>
 * FramePacer pacer = new FramePacer(30); // 30 fps
 * while (processingFrames) {
 *     pacer.waitForNextFrame();
 *     // Process frame
 * }
 * </pre>
 */
public class FramePacer {

    /** The time interval between frames in nanoseconds. */
    private final long frameIntervalNanos;

    /** Timestamp of the last processed frame in nanoseconds. Used to calculate the appropriate delay between frames. */
    private long lastFrameTimeNanos;


    /**
     * Creates a FramePacer with the specified frame rate.
     *
     * @param fps Frames per second.
     */
    public FramePacer(int fps) {
        this.frameIntervalNanos = 1_000_000_000L / fps; // Convert to nanoseconds
        this.lastFrameTimeNanos = 0;
    }

    /**
     * Waits for the appropriate time before the next frame should be processed.
     */
    public void waitForNextFrame() {
        long now = System.nanoTime();

        if (lastFrameTimeNanos > 0) {
            long elapsed = now - lastFrameTimeNanos;

            if (elapsed < frameIntervalNanos) {
                long sleepTimeNanos = frameIntervalNanos - elapsed;
                try {
                    // Convert nanoseconds to milliseconds and nanosecond remainder.
                    long sleepTimeMillis = sleepTimeNanos / 1_000_000;
                    int remainderNanos = (int) (sleepTimeNanos % 1_000_000);

                    Thread.sleep(sleepTimeMillis, remainderNanos);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // Return early if interrupted.
                    return;
                }
            }
        }

        lastFrameTimeNanos = System.nanoTime();
    }

    public long getFrameIntervalNanos() {
        return frameIntervalNanos;
    }
}