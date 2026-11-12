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

package dev.onvoid.webrtc.examples;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

/**
 * Example letting the user choose a screen or window in the operating
 * system's own picker, instead of listing sources in the application.
 * <p>
 * On macOS 14 and newer, {@link VideoDesktopSource#start()} shows
 * ScreenCaptureKit's content sharing picker. The example prints the number and
 * size of the frames it receives, and reports when the track ends: when the
 * picker is cancelled, or the user stops sharing from the menu bar.
 * <p>
 * Run it with:
 * <pre>
 * mvn -pl webrtc-examples exec:java -Dexec.mainClass=dev.onvoid.webrtc.examples.DesktopPickerExample
 * </pre>
 *
 * @author Alex Andres
 */
public class DesktopPickerExample {

    public static void main(String[] args) throws Exception {
        if (!VideoDesktopSource.isSystemPickerAvailable()) {
            System.out.println("This system offers no picker for screens and windows.");
            return;
        }

        PeerConnectionFactory factory = new PeerConnectionFactory();
        VideoDesktopSource videoSource = new VideoDesktopSource();
        videoSource.setFrameRate(15);
        videoSource.setUseSystemPicker(true);

        VideoTrack videoTrack = factory.createVideoTrack("video0", videoSource);

        AtomicInteger frames = new AtomicInteger();
        AtomicReference<String> size = new AtomicReference<>("none");
        CountDownLatch ended = new CountDownLatch(1);

        VideoTrackSink sink = frame -> {
            size.set(frame.buffer.getWidth() + "x" + frame.buffer.getHeight());
            frames.incrementAndGet();
            frame.release();
        };

        // Add the listener before start(), so that it hears of a picker that
        // is cancelled at once.
        videoTrack.addTrackEndedListener(track -> {
            System.out.println("The track ended: the picker was cancelled, or sharing stopped.");
            ended.countDown();
        });
        videoTrack.addSink(sink);

        System.out.println("Choose a screen or window in the picker. Press Ctrl+C to quit.");
        videoSource.start();

        try {
            while (!ended.await(1, TimeUnit.SECONDS)) {
                System.out.printf("Frames: %d, last size: %s%n", frames.get(), size.get());
            }
        }
        finally {
            videoSource.stop();
            videoTrack.removeSink(sink);
            videoTrack.dispose();
            videoSource.dispose();
            factory.dispose();
        }
    }
}
