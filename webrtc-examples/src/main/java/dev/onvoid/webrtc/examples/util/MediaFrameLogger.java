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

package dev.onvoid.webrtc.examples.util;

import java.util.function.Consumer;

import dev.onvoid.webrtc.client.AudioChunk;
import dev.onvoid.webrtc.media.video.VideoFrame;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for logging audio and video frame information, as handlers of
 * the client API's {@code RemoteAudio.onAudio} and {@code RemoteVideo.onFrame}.
 * Each logger writes a summary once a second.
 *
 * @author Alex Andres
 */
public class MediaFrameLogger {

    private static final Logger LOG = LoggerFactory.getLogger(MediaFrameLogger.class);

    private static final long LOG_INTERVAL_MS = 1000;


    /**
     * Creates a handler that logs information about received audio.
     *
     * @return A handler for {@code RemoteAudio.onAudio}.
     */
    public static Consumer<AudioChunk> createAudioLogger() {
        return new AudioFrameLogger();
    }

    /**
     * Creates a handler that logs information about received video frames.
     *
     * @return A handler for {@code RemoteVideo.onFrame}.
     */
    public static Consumer<VideoFrame> createVideoLogger() {
        return new VideoFrameLogger();
    }



    /**
     * Logs the number of audio chunks received per second and the format of the last one.
     * The client API calls it on one thread, in order.
     */
    private static class AudioFrameLogger implements Consumer<AudioChunk> {

        private int chunkCount = 0;

        private long lastLogTime = System.currentTimeMillis();


        @Override
        public void accept(AudioChunk chunk) {
            chunkCount++;

            long now = System.currentTimeMillis();

            if (now - lastLogTime >= LOG_INTERVAL_MS) {
                LOG.info(String.format("Received %d audio chunks in the last %.1f seconds, the last one %s",
                        chunkCount, (now - lastLogTime) / 1000.0, chunk));

                chunkCount = 0;
                lastLogTime = now;
            }
        }
    }

    /**
     * Logs the number of video frames received per second and the size of the last one.
     * The frame is released by the client API once the handler returned.
     */
    private static class VideoFrameLogger implements Consumer<VideoFrame> {

        private int frameCount = 0;

        private long lastLogTime = System.currentTimeMillis();


        @Override
        public void accept(VideoFrame frame) {
            frameCount++;

            long now = System.currentTimeMillis();

            if (now - lastLogTime >= LOG_INTERVAL_MS) {
                LOG.info(String.format("Received %d video frames in the last %.1f seconds, the last one %dx%d, rotation %d",
                        frameCount, (now - lastLogTime) / 1000.0, frame.buffer.getWidth(),
                        frame.buffer.getHeight(), frame.rotation));

                frameCount = 0;
                lastLogTime = now;
            }
        }
    }
}
