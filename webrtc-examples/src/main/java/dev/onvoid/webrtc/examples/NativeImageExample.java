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

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomAudio;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.DataChannel;
import dev.onvoid.webrtc.client.PcmFormat;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.SignalMessage;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.logging.Logging;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * Example that checks itself, made to be compiled into a GraalVM native image: two peers in one
 * process send each other video, audio and data channel messages, and the example exits with
 * status 0 only if everything arrived.
 * <p>
 * A native image knows only the classes and members it was told about, and the native library
 * calls into Java from threads of its own. This example goes through the parts where that shows:
 * <ul>
 *   <li>Loading the native library, which the image carries inside itself</li>
 *   <li>Callbacks from WebRTC's threads: negotiation, ICE candidates, tracks, channel
 *   messages</li>
 *   <li>Frames and audio that the native code hands to Java, and statistics it builds as Java
 *   objects</li>
 *   <li>A log sink, called by WebRTC's logging from any thread</li>
 * </ul>
 * <p>
 * Build the image and run it, with a GraalVM as {@code JAVA_HOME}:
 * <pre>
 * mvn -pl webrtc-examples -Pnative package
 * webrtc-examples/target/webrtc-native-image-example
 * </pre>
 * It runs on the JVM as well:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.NativeImageExample"
 * </pre>
 *
 * @author Alex Andres
 */
public class NativeImageExample {

    private static final int WIDTH = 320;

    private static final int HEIGHT = 180;

    private static final PcmFormat AUDIO_FORMAT = PcmFormat.of(48000, 1);

    /** How long everything may take, from start to the last check. */
    private static final long TIMEOUT_SECONDS = 30;


    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();

        try {
            run(failures);
        }
        catch (Throwable e) {
            e.printStackTrace();
            failures.add("Failed with " + e);
        }

        if (!failures.isEmpty()) {
            failures.forEach(failure -> System.out.println("FAILED: " + failure));
            System.exit(1);
        }

        System.out.println("OK");
    }

    private static void run(List<String> failures) throws Exception {
        CountDownLatch logged = new CountDownLatch(1);
        CountDownLatch videoReceived = new CountDownLatch(30);
        CountDownLatch audioReceived = new CountDownLatch(1);
        CountDownLatch echoed = new CountDownLatch(1);
        CountDownLatch statsRead = new CountDownLatch(1);
        AtomicLong audioMillis = new AtomicLong();

        // Called by WebRTC's logging, on whichever thread logs.
        Logging.addLogSink(Logging.Severity.INFO, (severity, message) -> logged.countDown());

        try (WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build()) {
            AtomicReference<Peer> callerRef = new AtomicReference<>();
            AtomicReference<Peer> calleeRef = new AtomicReference<>();

            Peer caller = rtc.createPeer(PeerOptions.builder()
                    .name("caller")
                    .signaling(message -> transmit(calleeRef.get(), message))
                    .build());
            Peer callee = rtc.createPeer(PeerOptions.builder()
                    .name("callee")
                    .polite(true)
                    .signaling(message -> transmit(callerRef.get(), message))
                    .build());

            callerRef.set(caller);
            calleeRef.set(callee);

            callee.onTrack(track -> {
                if (track.isVideo()) {
                    track.asVideo().onFrame(frame -> videoReceived.countDown());
                }
                else {
                    track.asAudio().onAudio(chunk -> {
                        long millis = audioMillis.addAndGet(chunk.frames() * 1000L / chunk.sampleRate());

                        if (millis >= 500) {
                            audioReceived.countDown();
                        }
                    });
                }
            });
            callee.onChannel(channel -> channel.onMessage(message -> channel.send("Echo: " + message.text())));

            CustomVideo video = rtc.media().customVideo("pattern");
            CustomAudio audio = rtc.media().customAudio("tone");
            caller.addTrack(video);
            caller.addTrack(audio);

            DataChannel chat = caller.createChannel("chat");
            chat.onMessage(message -> {
                if (message.text().equals("Echo: Hello")) {
                    echoed.countDown();
                }
            });
            chat.send("Hello");

            caller.onStats(Duration.ofMillis(500), stats -> {
                if (stats.candidateType() != null) {
                    statsRead.countDown();
                }
            });

            ScheduledExecutorService feeder = Executors.newSingleThreadScheduledExecutor();

            try {
                feed(feeder, video, audio);

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

                caller.whenConnected().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                System.out.println("Connected");

                check(failures, "30 video frames received", videoReceived, deadline);
                check(failures, "500 ms of audio received", audioReceived, deadline);
                check(failures, "data channel message echoed", echoed, deadline);
                check(failures, "statistics with the candidate pair read", statsRead, deadline);
                check(failures, "log sink called", logged, deadline);
            }
            finally {
                feeder.shutdownNow();
            }
        }
    }

    private static void check(List<String> failures, String what, CountDownLatch latch, long deadline)
            throws InterruptedException {
        long remaining = Math.max(0, deadline - System.nanoTime());

        if (latch.await(remaining, TimeUnit.NANOSECONDS)) {
            System.out.println("Checked: " + what);
        }
        else {
            failures.add(what);
        }
    }

    /**
     * Carries a message as JSON text, as a WebSocket would.
     */
    private static void transmit(Peer to, SignalMessage message) {
        to.signal(SignalMessage.fromJson(message.toJson()));
    }

    /**
     * Pushes a moving gradient at 30 frames per second, and a 440 Hz tone a second at a time.
     */
    private static void feed(ScheduledExecutorService feeder, CustomVideo video, CustomAudio audio) {
        AtomicInteger shade = new AtomicInteger();

        feeder.scheduleAtFixedRate(() -> {
            NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);
            ByteBuffer y = buffer.getDataY();
            int offset = shade.addAndGet(2);

            for (int i = 0; i < y.capacity(); i++) {
                y.put(i, (byte) (i % WIDTH + offset));
            }

            VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
            video.push(frame);
            frame.release();
        }, 0, 33, TimeUnit.MILLISECONDS);

        byte[] second = new byte[AUDIO_FORMAT.sampleRate() * 2];

        for (int i = 0; i < AUDIO_FORMAT.sampleRate(); i++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * i / AUDIO_FORMAT.sampleRate()) * 6000);

            second[2 * i] = (byte) sample;
            second[2 * i + 1] = (byte) (sample >> 8);
        }

        feeder.scheduleAtFixedRate(() -> audio.push(second, AUDIO_FORMAT), 0, 1, TimeUnit.SECONDS);
    }

}
