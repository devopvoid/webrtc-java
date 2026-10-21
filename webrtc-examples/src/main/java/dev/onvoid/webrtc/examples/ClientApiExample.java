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
import dev.onvoid.webrtc.client.VideoStats;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;

/**
 * Example of the high-level client API ({@code dev.onvoid.webrtc.client}): two peers in one
 * process send each other video, audio and chat messages.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Create a {@link WebRtc} runtime that needs no audio hardware</li>
 *   <li>Connect two peers through a signaling that carries JSON text, as a WebSocket would</li>
 *   <li>Let perfect negotiation run the offer and answer exchange, with one polite peer</li>
 *   <li>Send pushed video frames and pushed audio of any length</li>
 *   <li>Receive remote tracks, their frames and their audio</li>
 *   <li>Exchange messages on a data channel</li>
 *   <li>Read the connection's statistics once a second</li>
 * </ul>
 * <p>
 * No observer, no {@code dispose()} and no thread of its own is needed: handlers run on the event
 * thread of their peer, and closing the runtime closes everything it created.
 * <p>
 * Run it for a number of seconds, five by default:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.ClientApiExample" -D"exec.args=10"
 * </pre>
 *
 * @author Alex Andres
 */
public class ClientApiExample {

    private static final int WIDTH = 640;

    private static final int HEIGHT = 360;

    private static final PcmFormat AUDIO_FORMAT = PcmFormat.of(48000, 1);


    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 5;

        // A headless runtime sends pushed audio and needs no microphone or speakers.
        try (WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build()) {
            AtomicReference<Peer> callerRef = new AtomicReference<>();
            AtomicReference<Peer> calleeRef = new AtomicReference<>();

            // Each signaling turns messages into JSON text and hands that text to the other peer.
            // A real application sends it through its WebSocket instead.
            Peer caller = rtc.createPeer(PeerOptions.builder()
                    .name("caller")
                    .signaling(message -> transmit("caller", calleeRef.get(), message.toJson()))
                    .build());
            Peer callee = rtc.createPeer(PeerOptions.builder()
                    .name("callee")
                    .polite(true)
                    .signaling(message -> transmit("callee", callerRef.get(), message.toJson()))
                    .build());

            callerRef.set(caller);
            calleeRef.set(callee);

            receive(callee);
            caller.onStateChange(state -> System.out.println("Caller is " + state));

            // Pushed media. The same tracks could be sent on any number of peers.
            CustomVideo video = rtc.media().customVideo("pattern");
            CustomAudio audio = rtc.media().customAudio("tone");
            caller.addTrack(video);
            caller.addTrack(audio);

            // Sent once the channel opens.
            DataChannel chat = caller.createChannel("chat");
            chat.onMessage(message -> System.out.println("Caller got \"" + message.text() + "\""));
            chat.send("Hello from the caller");

            caller.onStats(Duration.ofSeconds(1), stats -> {
                if (stats.candidateType() == null) {
                    // Not connected yet.
                    return;
                }

                String size = stats.outboundVideo().isEmpty() ? "-" : describe(stats.outboundVideo().get(0));

                System.out.printf("Caller sends %d kbit/s over a %s candidate, video %s%n",
                        Math.max(0, stats.outgoingBitrate()) / 1000, stats.candidateType(), size);
            });

            ScheduledExecutorService feeder = Executors.newSingleThreadScheduledExecutor();

            try {
                feed(feeder, video, audio);

                // The main thread may wait; a handler must not.
                caller.whenConnected().get(10, TimeUnit.SECONDS);

                Thread.sleep(seconds * 1000L);
            }
            finally {
                feeder.shutdownNow();
            }
        }
        // The runtime closed the peers, the tracks and the factory, in that order.

        System.out.println("Closed");
    }

    /**
     * Prints what the callee receives.
     */
    private static void receive(Peer callee) {
        AtomicInteger frames = new AtomicInteger();
        AtomicLong audioMillis = new AtomicLong();

        callee.onTrack(track -> {
            String kind = track.kind().name().toLowerCase();

            System.out.println("Callee receives the " + kind + " track in stream " + track.streamIds());

            if (track.isVideo()) {
                // A slow handler gets the newest frame next; frames in between are dropped.
                track.asVideo().onFrame(frame -> {
                    if (frames.incrementAndGet() % 30 == 1) {
                        System.out.printf("Callee got video frame %d, %dx%d%n", frames.get(),
                                frame.buffer.getWidth(), frame.buffer.getHeight());
                    }
                });
            }
            else {
                track.asAudio().onAudio(chunk -> {
                    long millis = audioMillis.addAndGet(chunk.frames() * 1000L / chunk.sampleRate());

                    if (millis % 1000 == 0) {
                        System.out.printf("Callee got %d ms of audio%n", millis);
                    }
                });
            }

            track.onEnded(() -> System.out.println("Callee's " + kind + " track ended"));
        });

        // Channels the caller opens. Messages that arrive before onMessage are kept for it.
        callee.onChannel(channel -> channel.onMessage(message -> {
            System.out.println("Callee got \"" + message.text() + "\"");
            channel.send("Echo: " + message.text());
        }));
    }

    /**
     * Stands in for a WebSocket: carries a message as text and reads it on the other side.
     */
    private static void transmit(String from, Peer to, String json) {
        SignalMessage message = SignalMessage.fromJson(json);

        if (message.type() != SignalMessage.Type.CANDIDATE) {
            System.out.printf("%s sends an %s of %d characters%n", from,
                    message.type().name().toLowerCase(), json.length());
        }

        to.signal(message);
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

        // Audio may be pushed in pieces of any length; the track sends it in 10 ms chunks.
        byte[] second = tone(AUDIO_FORMAT.sampleRate());

        feeder.scheduleAtFixedRate(() -> audio.push(second, AUDIO_FORMAT), 0, 1, TimeUnit.SECONDS);
    }

    private static byte[] tone(int sampleRate) {
        byte[] pcm = new byte[sampleRate * 2];

        for (int i = 0; i < sampleRate; i++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 6000);

            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }

        return pcm;
    }

    private static String describe(VideoStats video) {
        return String.format("%s %dx%d at %.0f fps", video.codec(), video.width(), video.height(),
                video.framesPerSecond());
    }
}
