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

package dev.onvoid.webrtc.examples;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.client.Whep;

/**
 * Example of a WebRTC HTTP Egress Protocol (WHEP) client, with the client API.
 * <p>
 * This class demonstrates:
 * <ul>
 *   <li>Receiving a stream from a WHEP endpoint with {@link Whep}</li>
 *   <li>Logging the stream's video frames and audio</li>
 *   <li>Ending the session, which deletes it on the server</li>
 * </ul>
 * <p>
 * {@link Whep} sends an offer, trickles the local candidates after it, applies the answer and
 * waits until the peer is connected. The Client API guide shows the same steps by hand, through the
 * peer's negotiator, for servers that speak a protocol of their own.
 * <p>
 * Run it against a WHEP endpoint, such as one of MediaMTX, for a number of seconds:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.WhepExample" -D"exec.args=http://localhost:8889/mystream/whep 10"
 * </pre>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9725">WHEP and WHIP</a>
 *
 * @author Alex Andres
 */
public class WhepExample {

    private static final String DEFAULT_ENDPOINT = "http://localhost:8889/mystream/whep";


    public static void main(String[] args) throws Exception {
        URI endpoint = URI.create(args.length > 0 ? args[0] : DEFAULT_ENDPOINT);
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        // The runtime plays the received audio to the speakers.
        try (WebRtc rtc = WebRtc.create()) {
            System.out.println("Requesting the stream from " + endpoint);

            // The main thread may wait for the session; a handler must not.
            // For endpoints without Trickle ICE, add .gathering(Gathering.COMPLETE).
            // Authenticated TURN is configured with .turnServer(url, username, password).
            Whep.Session session = Whep.from(endpoint)
                    .iceServer("stun:stun.l.google.com:19302")
                    .play(rtc)
                    .get(15, TimeUnit.SECONDS);

            Peer viewer = session.peer();
            viewer.onStateChange(state -> System.out.println("Connection is " + state));

            // Tracks that arrived before this handler are handed to it too.
            viewer.onTrack(track -> {
                System.out.println("Receiving " + track.kind());

                if (track.isVideo()) {
                    AtomicInteger frames = new AtomicInteger();

                    track.asVideo().onFrame(frame -> {
                        if (frames.incrementAndGet() % 100 == 1) {
                            System.out.printf("Video frame %d: %dx%d%n", frames.get(),
                                    frame.buffer.getWidth(), frame.buffer.getHeight());
                        }
                    });
                }
                else {
                    AtomicLong millis = new AtomicLong();

                    track.asAudio().onAudio(chunk -> {
                        if (millis.addAndGet(chunk.frames() * 1000L / chunk.sampleRate()) % 2000 == 0) {
                            System.out.printf("Received %d ms of audio%n", millis.get());
                        }
                    });
                }
            });

            Thread.sleep(seconds * 1000L);

            // Deletes the session on the server and closes the peer.
            session.closeAsync().get(5, TimeUnit.SECONDS);

            System.out.println("Ended the session " + session.resource());
        }
        // The runtime closed the factory.
    }
}
