/*
 * Copyright 2025 Alex Andres
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

package dev.onvoid.webrtc.examples.web.media;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A generator that produces synthetic video frames.
 * <p>
 * This class creates a color pattern that changes over time and delivers video frames
 * at regular intervals (30 fps by default). The generated video is in I420 format
 * with a default resolution of 640x480. The video generator runs on a dedicated scheduled
 * thread that can be started and stopped on demand.
 * </p>
 *
 * @author Alex Andres
 */
public class VideoGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(VideoGenerator.class);

    /** The custom video source that receives generated video frames. */
    private final CustomVideoSource customVideoSource;

    /** Flag indicating whether the video generator is running. */
    private final AtomicBoolean generatorRunning = new AtomicBoolean(false);

    /** Counter for frame animation. */
    private final AtomicInteger frameCounter = new AtomicInteger(0);

    /** Executor service for scheduling video frame generation. */
    private ScheduledExecutorService executorService;

    /** Future for the scheduled video frame generation task. */
    private ScheduledFuture<?> generatorFuture;

    /** Default video parameters */
    private static final int DEFAULT_WIDTH = 640;
    private static final int DEFAULT_HEIGHT = 480;
    private static final int DEFAULT_FPS = 30;
    private static final int FRAME_INTERVAL_MS = 1000 / DEFAULT_FPS;


    /**
     * Creates a new VideoGenerator that will push frames to the specified video source.
     *
     * @param videoSource The custom video source to receive the generated frames.
     */
    public VideoGenerator(CustomVideoSource videoSource) {
        customVideoSource = videoSource;
    }

    /**
     * Starts the video frame generator thread that pushes video frames
     * to the custom video source at the specified frame rate.
     */
    public void start() {
        if (generatorRunning.get()) {
            LOG.info("Video generator is already running");
            return;
        }

        executorService = Executors.newSingleThreadScheduledExecutor();
        generatorRunning.set(true);

        generatorFuture = executorService.scheduleAtFixedRate(() -> {
            if (!generatorRunning.get()) {
                return;
            }

            try {
                // Create a new video frame with a color pattern.
                VideoFrame frame = generateVideoFrame();
                
                // Push the frame to the custom video source.
                customVideoSource.pushFrame(frame);
                
                // Release the frame after pushing it.
                frame.release();
                
                // Increment frame counter for animation.
                frameCounter.incrementAndGet();
            }
            catch (Exception e) {
                LOG.error("Error in video generator thread", e);
            }
        }, 0, FRAME_INTERVAL_MS, TimeUnit.MILLISECONDS);

        LOG.info("Video generator started at {} fps", DEFAULT_FPS);
    }

    /**
     * Stops the video frame generator thread.
     */
    public void stop() {
        if (!generatorRunning.get()) {
            return;
        }

        generatorRunning.set(false);

        if (generatorFuture != null) {
            generatorFuture.cancel(false);
            generatorFuture = null;
        }

        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow();
                }
            }
            catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executorService = null;
        }

        LOG.info("Video generator stopped");
    }

    /**
     * Generates a video frame with a color pattern that changes over time.
     *
     * @return A new VideoFrame with the generated pattern.
     */
    private VideoFrame generateVideoFrame() {
        // Allocate a new I420 buffer for the frame
        NativeI420Buffer buffer = NativeI420Buffer.allocate(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        
        // Get the Y, U, V planes
        ByteBuffer dataY = buffer.getDataY();
        ByteBuffer dataU = buffer.getDataU();
        ByteBuffer dataV = buffer.getDataV();

        int strideY = buffer.getStrideY();
        int strideU = buffer.getStrideU();
        int strideV = buffer.getStrideV();

        // Generate a color pattern
        fillFrameWithPattern(dataY, dataU, dataV, strideY, strideU, strideV, frameCounter.get());

        // Create a new video frame with the buffer and current timestamp
        return new VideoFrame(buffer, System.nanoTime());
    }

    /**
     * Fills the frame buffers with a color pattern.
     * This creates a simple test pattern that changes over time.
     *
     * @param dataY      The Y plane buffer.
     * @param dataU      The U plane buffer.
     * @param dataV      The V plane buffer.
     * @param strideY    The Y plane stride.
     * @param strideU    The U plane stride.
     * @param strideV    The V plane stride.
     * @param frameCount The current frame count for animation.
     */
    private void fillFrameWithPattern(ByteBuffer dataY, ByteBuffer dataU, ByteBuffer dataV,
                                      int strideY, int strideU, int strideV, int frameCount) {
        // Reset buffer positions
        dataY.position(0);
        dataU.position(0);
        dataV.position(0);

        // Animation parameters
        int animOffset = frameCount % 255;

        // Fill Y plane (luma)
        for (int y = 0; y < DEFAULT_HEIGHT; y++) {
            for (int x = 0; x < DEFAULT_WIDTH; x++) {
                // Create a gradient pattern
                byte value = (byte) ((x + y + animOffset) % 255);
                dataY.put(y * strideY + x, value);
            }
        }

        // Fill U and V planes (chroma)
        // In I420 format, U and V planes are quarter size of Y plane
        for (int y = 0; y < DEFAULT_HEIGHT / 2; y++) {
            for (int x = 0; x < DEFAULT_WIDTH / 2; x++) {
                // Create color patterns that change over time
                byte uValue = (byte) ((x * 2 + animOffset) % 255);
                byte vValue = (byte) ((y * 2 + animOffset) % 255);

                dataU.put(y * strideU + x, uValue);
                dataV.put(y * strideV + x, vValue);
            }
        }
    }
}