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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.client.Gathering;
import dev.onvoid.webrtc.client.MediaKind;
import dev.onvoid.webrtc.client.Negotiation;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.WebRtc;

/**
 * Example implementation of a WebRTC HTTP Egress Protocol (WHEP) client, with the client API.
 * <p>
 * This class demonstrates:
 * <ul>
 *   <li>Creating a receive-only peer that negotiates by hand</li>
 *   <li>Creating an offer that already holds all local candidates, as WHEP servers expect</li>
 *   <li>Sending the offer to a WHEP endpoint and applying the answer it returns</li>
 *   <li>Receiving the stream's video and audio</li>
 *   <li>Ending the session with an HTTP DELETE of the resource the server created</li>
 * </ul>
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

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();


    public static void main(String[] args) throws Exception {
        URI endpoint = URI.create(args.length > 0 ? args[0] : DEFAULT_ENDPOINT);
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 10;

        // The runtime plays the received audio to the speakers.
        try (WebRtc rtc = WebRtc.create()) {
            // A WHEP server dictates the exchange: one offer, one answer, over HTTP.
            Peer viewer = rtc.createPeer(PeerOptions.builder()
                    .name("whep")
                    .iceServer("stun:stun.l.google.com:19302")
                    .negotiation(Negotiation.MANUAL)
                    .receive(MediaKind.VIDEO, MediaKind.AUDIO)
                    .build());

            viewer.onStateChange(state -> System.out.println("Connection is " + state));
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

            // The main thread may wait for the futures; a handler must not.
            RTCSessionDescription offer = viewer.negotiator()
                    .createOffer(Gathering.COMPLETE)
                    .get(10, TimeUnit.SECONDS);

            System.out.println("Sending the offer to " + endpoint);

            HttpResponse<String> response = post(endpoint, offer.sdp);
            URI resource = response.headers().firstValue("Location").map(endpoint::resolve).orElse(null);

            try {
                viewer.negotiator()
                        .applyAnswer(new RTCSessionDescription(RTCSdpType.ANSWER, response.body()))
                        .get(10, TimeUnit.SECONDS);
                viewer.whenConnected().get(10, TimeUnit.SECONDS);

                Thread.sleep(seconds * 1000L);
            }
            finally {
                if (resource != null) {
                    end(resource);
                }
            }
        }
        // The runtime closed the peer and the factory.
    }

    /**
     * Sends the offer and returns the server's answer.
     */
    private static HttpResponse<String> post(URI endpoint, String sdp) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/sdp")
                .POST(HttpRequest.BodyPublishers.ofString(sdp))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IllegalStateException("The WHEP endpoint answered with status " + response.statusCode()
                    + ": " + response.body());
        }

        return response;
    }

    /**
     * Ends the session on the server, which frees what it holds for this viewer.
     */
    private static void end(URI resource) {
        try {
            HttpRequest request = HttpRequest.newBuilder(resource)
                    .DELETE()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();

            System.out.println("Ended the session, status " + status);
        }
        catch (Exception e) {
            System.err.println("Ending the session failed: " + e);
        }
    }
}
