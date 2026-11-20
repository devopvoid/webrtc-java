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

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.CustomVideo;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.livekit.LiveKit;
import dev.onvoid.webrtc.livekit.Publication;
import dev.onvoid.webrtc.livekit.PublishOptions;
import dev.onvoid.webrtc.livekit.Room;
import dev.onvoid.webrtc.livekit.TrackSource;
import dev.onvoid.webrtc.livekit.VideoQuality;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * Example of a LiveKit client, with the webrtc-java-livekit module.
 * <p>
 * This class demonstrates:
 * <ul>
 *   <li>Joining a LiveKit room with an access token</li>
 *   <li>Publishing a video track in simulcast layers</li>
 *   <li>Receiving the other participants' video, in the smallest layer</li>
 *   <li>Sending and receiving data messages by topic</li>
 *   <li>Following participants, and the reconnects after a lost connection</li>
 * </ul>
 * <p>
 * The video is a test pattern the example pushes, so no camera is needed, and the runtime is
 * headless, so no microphone or speakers either.
 * <p>
 * Start a LiveKit server with {@code livekit-server --dev}, then run the example twice, in two
 * shells, to see the two participants receive each other:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.LiveKitExample" -D"exec.args=ws://localhost:7880 lecture alice 30"
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.LiveKitExample" -D"exec.args=ws://localhost:7880 lecture bob 30"
 * </pre>
 * Against another server, set the environment variable {@code LIVEKIT_TOKEN} to a token for the
 * room, or {@code LIVEKIT_API_KEY} and {@code LIVEKIT_API_SECRET} to the server's key and secret.
 *
 * @author Alex Andres
 */
public class LiveKitExample {

    private static final int WIDTH = 1280;

    private static final int HEIGHT = 720;


    public static void main(String[] args) throws Exception {
        URI url = URI.create(args.length > 0 ? args[0] : "ws://localhost:7880");
        String roomName = args.length > 1 ? args[1] : "lecture";
        String identity = args.length > 2 ? args[2] : "java-" + UUID.randomUUID().toString().substring(0, 8);
        int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 30;

        // Normally the application's backend issues the token.
        String token = System.getenv("LIVEKIT_TOKEN");
        if (token == null) {
            token = devToken(identity, roomName);
        }

        // Pushes the video and sends the slide events.
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

        try (WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build()) {
            System.out.println("Joining the room " + roomName + " on " + url + " as " + identity);

            // The main thread may wait for the room; a handler must not.
            Room room = LiveKit.room(rtc, url, token)
                    .connect()
                    .get(15, TimeUnit.SECONDS);
            CustomVideo video = rtc.media().customVideo("pattern");

            try {
                run(room, video, timer, seconds);
            }
            finally {
                timer.shutdownNow();
                timer.awaitTermination(1, TimeUnit.SECONDS);

                // Leaves the room and closes its peers; the track stays ours.
                room.close();
                video.close();
            }
        }
        finally {
            // Also if joining failed.
            timer.shutdownNow();
        }
        // The runtime closed the factory.
    }

    /**
     * Follows the room, publishes the video and sends a slide event every five seconds.
     */
    private static void run(Room room, CustomVideo video, ScheduledExecutorService timer, int seconds)
            throws Exception {
        System.out.println("Joined as " + room.localParticipant().sid() + ", with "
                + room.remoteParticipants().size() + " other participants");

        // Handlers run on the room's thread, one at a time.
        room.onParticipantJoined(participant -> System.out.println(participant.identity() + " joined"));
        room.onParticipantLeft(participant -> System.out.println(participant.identity() + " left"));

        room.onTrackSubscribed((participant, track) -> {
            System.out.println("Receiving " + track.kind() + " " + track.id() + " of " + participant.identity());

            if (track.isVideo()) {
                // A thumbnail needs no more than the smallest layer.
                room.requestVideoQuality(track.id(), VideoQuality.LOW);

                AtomicInteger frames = new AtomicInteger();
                track.asVideo().onFrame(frame -> {
                    if (frames.incrementAndGet() % 100 == 1) {
                        System.out.printf("Video frame %d of %s: %dx%d%n", frames.get(),
                                participant.identity(), frame.buffer.getWidth(), frame.buffer.getHeight());
                    }
                });
            }
        });

        room.onData(message -> System.out.println(message.senderIdentity() + " sent "
                + message.topic().orElse("data") + ": "
                + new String(message.payload(), StandardCharsets.UTF_8)));

        room.onReconnecting(reason -> System.out.println("Reconnecting: " + reason));
        room.onReconnected(() -> System.out.println("Reconnected as " + room.localParticipant().sid()));
        room.onDisconnected(reason -> System.out.println("Disconnected: " + reason));

        // A pushed test pattern, sent in three layers: 320x180, 640x360 and 1280x720.
        AtomicInteger frameNumber = new AtomicInteger();
        timer.scheduleAtFixedRate(() -> push(video, frameNumber.getAndIncrement()),
                0, 66, TimeUnit.MILLISECONDS);

        Publication publication = room.publish(video, PublishOptions.builder()
                .source(TrackSource.CAMERA)
                .videoSize(WIDTH, HEIGHT)
                .simulcast(true)
                .build())
                .get(15, TimeUnit.SECONDS);

        System.out.println("Publishing " + publication.sid() + " in the layers " + publication.rids());

        // Events everyone in the room receives, such as the slide a lecture is on.
        AtomicInteger slide = new AtomicInteger();
        timer.scheduleAtFixedRate(() -> {
            String event = "slide " + slide.incrementAndGet();
            room.publishData(event.getBytes(StandardCharsets.UTF_8), "slide", true);
        }, 5, 5, TimeUnit.SECONDS);

        Thread.sleep(seconds * 1000L);
    }

    /**
     * Draws a bar that moves across a grey frame and pushes it.
     */
    private static void push(CustomVideo video, int number) {
        NativeI420Buffer buffer = NativeI420Buffer.allocate(WIDTH, HEIGHT);

        byte[] row = new byte[WIDTH];
        Arrays.fill(row, (byte) 80);
        int bar = (number * 8) % WIDTH;
        Arrays.fill(row, bar, Math.min(bar + 64, WIDTH), (byte) 235);

        ByteBuffer y = buffer.getDataY();
        for (int line = 0; line < HEIGHT; line++) {
            y.put(line * buffer.getStrideY(), row);
        }
        fill(buffer.getDataU(), (byte) 128);
        fill(buffer.getDataV(), (byte) 128);

        VideoFrame frame = new VideoFrame(buffer, System.nanoTime());
        try {
            video.push(frame);
        }
        finally {
            frame.release();
        }
    }

    private static void fill(ByteBuffer plane, byte value) {
        for (int i = 0; i < plane.capacity(); i++) {
            plane.put(i, value);
        }
    }

    /**
     * Signs a token for one room, ten minutes long, as a backend would. The key and secret are
     * those of a server started with --dev, unless LIVEKIT_API_KEY and LIVEKIT_API_SECRET name
     * others. Never ship the secret in a client.
     */
    private static String devToken(String identity, String room) throws Exception {
        String key = System.getenv().getOrDefault("LIVEKIT_API_KEY", "devkey");
        String secret = System.getenv().getOrDefault("LIVEKIT_API_SECRET", "secret");
        long now = System.currentTimeMillis() / 1000;

        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String claims = "{\"iss\":\"" + key + "\",\"sub\":\"" + identity + "\",\"name\":\"" + identity + "\""
                + ",\"nbf\":" + (now - 10) + ",\"exp\":" + (now + 600)
                + ",\"video\":{\"room\":\"" + room + "\",\"roomJoin\":true,\"canPublish\":true"
                + ",\"canSubscribe\":true,\"canPublishData\":true}}";

        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String unsigned = base64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        return unsigned + "." + base64.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.UTF_8)));
    }
}
