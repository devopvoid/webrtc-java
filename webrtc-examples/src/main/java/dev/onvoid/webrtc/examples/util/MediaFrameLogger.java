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

import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for logging audio and video frame information.
 *
 * @author Alex Andres
 */
public class MediaFrameLogger {

    private static final Logger LOG = LoggerFactory.getLogger(MediaFrameLogger.class);


    /**
     * Creates a new audio track sink that logs information about received audio data.
     *
     * @return An AudioTrackSink that logs audio frame information.
     */
    public static AudioTrackSink createAudioLogger() {
        return new AudioFrameLogger();
    }

    /**
     * Creates a new video track sink that logs information about received video frames.
     *
     * @return A VideoTrackSink that logs video frame information.
     */
    public static VideoTrackSink createVideoLogger() {
        return new VideoFrameLogger();
    }



    /**
     * A simple implementation of AudioTrackSink that logs information about received audio data.
     */
    private static class AudioFrameLogger implements AudioTrackSink {
        
        private static final long LOG_INTERVAL_MS = 1000; // Log every second
        private int frameCount = 0;
        private long lastLogTime = System.currentTimeMillis();
        
        @Override
        public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
            frameCount++;
            
            long now = System.currentTimeMillis();
            if (now - lastLogTime >= LOG_INTERVAL_MS) {
                LOG.info(String.format("Received %d audio frames in the last %.1f seconds",
                        frameCount, (now - lastLogTime) / 1000.0));
                LOG.info(String.format("Last audio data: %d bytes, %d bits/sample, %d Hz, %d channels, %d frames",
                        data.length, bitsPerSample, sampleRate, channels, frames));
                
                frameCount = 0;
                lastLogTime = now;
            }
        }
    }



    /**
     * A simple implementation of VideoTrackSink that logs information about received frames.
     */
    private static class VideoFrameLogger implements VideoTrackSink {
        
        private static final long LOG_INTERVAL_MS = 1000; // Log every second
        private int frameCount = 0;
        private long lastLogTime = System.currentTimeMillis();
        
        @Override
        public void onVideoFrame(VideoFrame frame) {
            frameCount++;
            
            long now = System.currentTimeMillis();
            if (now - lastLogTime >= LOG_INTERVAL_MS) {
                LOG.info(String.format("Received %d video frames in the last %.1f seconds",
                        frameCount, (now - lastLogTime) / 1000.0));
                LOG.info(String.format("Last frame: %dx%d, rotation: %d, timestamp: %dms",
                        frame.buffer.getWidth(), frame.buffer.getHeight(), frame.rotation,
                        frame.timestampNs / 1000000));
                
                frameCount = 0;
                lastLogTime = now;
            }
            
            // Release the native resources associated with this frame to prevent memory leaks.
            frame.release();
        }
    }
}