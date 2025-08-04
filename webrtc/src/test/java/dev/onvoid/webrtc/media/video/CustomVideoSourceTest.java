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

package dev.onvoid.webrtc.media.video;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.TestBase;
import dev.onvoid.webrtc.media.MediaSource;
import dev.onvoid.webrtc.media.SyncClock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustomVideoSourceTest extends TestBase {

    private CustomVideoSource customVideoSource;


    @BeforeEach
    void init() {
        customVideoSource = new CustomVideoSource();
    }

    @AfterEach
    void dispose() {
        if (customVideoSource != null) {
            customVideoSource.dispose();
            customVideoSource = null;
        }
    }

    @Test
    void stateAfterCreation() {
        assertEquals(MediaSource.State.LIVE, customVideoSource.getState());
    }

    @Test
    void addNullSink() {
        assertThrows(NullPointerException.class, () -> {
            VideoTrack videoTrack = factory.createVideoTrack("videoTrack", customVideoSource);
            videoTrack.addSink(null);
            videoTrack.dispose();
        });
    }

    @Test
    void removeNullSink() {
        assertThrows(NullPointerException.class, () -> {
            VideoTrack videoTrack = factory.createVideoTrack("videoTrack", customVideoSource);
            videoTrack.removeSink(null);
            videoTrack.dispose();
        });
    }

    @Test
    void addRemoveSink() {
        VideoTrack videoTrack = factory.createVideoTrack("videoTrack", customVideoSource);
        VideoTrackSink sink = new VideoTrackSink() {
            @Override
            public void onVideoFrame(VideoFrame frame) {
                // Do nothing
            }
        };

        videoTrack.addSink(sink);
        videoTrack.removeSink(sink);
        videoTrack.dispose();
    }

    @Test
    void pushVideoFrame() {
        // Test with 640x480 resolution
        testVideoFrame(640, 480);
    }

    @Test
    void pushVideoFrameWithDifferentResolutions() {
        testVideoFrame(320, 240);  // QVGA
        testVideoFrame(640, 480);  // VGA
        testVideoFrame(1280, 720); // HD
        testVideoFrame(1920, 1080); // Full HD
    }
    
    @Test
    void constructWithSyncClock() {
        // Create a SyncClock.
        SyncClock clock = new SyncClock();
        
        // Create a CustomVideoSource with the clock.
        CustomVideoSource sourceWithClock = new CustomVideoSource(clock);
        
        // Verify the source is created correctly.
        assertEquals(MediaSource.State.LIVE, sourceWithClock.getState());
        
        // Test basic functionality.
        VideoTrack videoTrack = factory.createVideoTrack("videoTrack", sourceWithClock);
        
        final AtomicBoolean frameReceived = new AtomicBoolean(false);
        final AtomicInteger receivedWidth = new AtomicInteger(0);
        final AtomicInteger receivedHeight = new AtomicInteger(0);
        
        VideoTrackSink testSink = new VideoTrackSink() {
            @Override
            public void onVideoFrame(VideoFrame frame) {
                frameReceived.set(true);
                receivedWidth.set(frame.buffer.getWidth());
                receivedHeight.set(frame.buffer.getHeight());
            }
        };
        
        videoTrack.addSink(testSink);
        
        // Create a test frame.
        int width = 640;
        int height = 480;
        NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
        VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
        
        // Push the frame to the source.
        sourceWithClock.pushFrame(frame);
        
        // Verify that our sink received the frame with correct parameters.
        assertTrue(frameReceived.get(), "Video frame was not received by the sink");
        assertEquals(width, receivedWidth.get(), "Frame width doesn't match");
        assertEquals(height, receivedHeight.get(), "Frame height doesn't match");
        
        // Clean up.
        frame.release();
        videoTrack.removeSink(testSink);
        videoTrack.dispose();
        sourceWithClock.dispose();
        clock.dispose();
    }

    private void testVideoFrame(int width, int height) {
        VideoTrack videoTrack = factory.createVideoTrack("videoTrack", customVideoSource);

        final AtomicBoolean frameReceived = new AtomicBoolean(false);
        final AtomicInteger receivedWidth = new AtomicInteger(0);
        final AtomicInteger receivedHeight = new AtomicInteger(0);

        VideoTrackSink testSink = new VideoTrackSink() {
            @Override
            public void onVideoFrame(VideoFrame frame) {
                frameReceived.set(true);
                receivedWidth.set(frame.buffer.getWidth());
                receivedHeight.set(frame.buffer.getHeight());
            }
        };

        videoTrack.addSink(testSink);

        // Create a test frame with the specified resolution
        NativeI420Buffer buffer = NativeI420Buffer.allocate(width, height);
        VideoFrame frame = new VideoFrame(buffer, System.nanoTime());

        // Push the frame to the source
        customVideoSource.pushFrame(frame);

        // Verify that our sink received the frame with correct parameters
        assertTrue(frameReceived.get(), "Video frame was not received by the sink");
        assertEquals(width, receivedWidth.get(), "Frame width doesn't match");
        assertEquals(height, receivedHeight.get(), "Frame height doesn't match");

        // Clean up
        frame.release();
        videoTrack.removeSink(testSink);
        videoTrack.dispose();
    }
}