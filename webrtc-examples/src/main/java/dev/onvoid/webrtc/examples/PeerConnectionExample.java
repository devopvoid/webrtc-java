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

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.onvoid.webrtc.client.Devices;
import dev.onvoid.webrtc.client.LocalAudio;
import dev.onvoid.webrtc.client.LocalCamera;
import dev.onvoid.webrtc.client.MediaException;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;

/**
 * Example demonstrating how to send a camera and a microphone over a peer connection, and how
 * to receive the video frames and audio on the other side, with the client API.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Create a {@link WebRtc} runtime that captures from and plays to the audio devices</li>
 *   <li>List the cameras and microphones</li>
 *   <li>Open the camera and the microphone as local tracks</li>
 *   <li>Connect two peers through a loopback signaling, in place of a server</li>
 *   <li>Send the tracks, and receive them as remote tracks with their frames and audio</li>
 *   <li>Mute a received audio track, while its audio still reaches a handler</li>
 * </ul>
 * <p>
 * Both peers run in this process, so the call connects without a server. In a real
 * application each peer is in another process, and its {@link Signaling} carries the messages
 * over a WebSocket or the like, as the web example does. Nothing else changes.
 * <p>
 * Run it, and press Enter to end the call:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.PeerConnectionExample"
 * </pre>
 *
 * @author Alex Andres
 */
public class PeerConnectionExample {

    public static void main(String[] args) throws Exception {
        // The runtime captures from the microphone and plays to the speakers selected in its
        // devices.
        try (WebRtc rtc = WebRtc.create()) {
            Devices devices = rtc.media().devices();

            devices.cameras().forEach(camera -> System.out.println("Camera: " + camera.getName()));
            devices.microphones().forEach(mic -> System.out.println("Microphone: " + mic.getName()));

            // The two ends of a loopback signaling hand each message to the other peer.
            SignalingPair signaling = Signaling.loopback();

            Peer caller = rtc.createPeer(PeerOptions.builder()
                    .name("caller")
                    .iceServer("stun:stun.l.google.com:19302")
                    .signaling(signaling.first())
                    .build());
            Peer callee = rtc.createPeer(PeerOptions.builder()
                    .name("callee")
                    .iceServer("stun:stun.l.google.com:19302")
                    .polite(true)
                    .signaling(signaling.second())
                    .build());

            caller.onStateChange(state -> System.out.println("Caller is " + state));
            callee.onTrack(PeerConnectionExample::receive);

            boolean sending = false;

            if (devices.cameras().isEmpty()) {
                System.out.println("No camera, sending no video");
            }
            else {
                try {
                    LocalCamera camera = rtc.media().camera();
                    caller.addTrack(camera);
                    sending = true;

                    System.out.println("Sending the camera " + camera.device().getName());
                }
                catch (MediaException e) {
                    System.out.println("The camera could not be opened: " + e.getMessage());
                }
            }

            if (devices.microphones().isEmpty()) {
                System.out.println("No microphone, sending no audio");
            }
            else {
                // Echo cancellation, noise suppression and gain control are on.
                LocalAudio microphone = rtc.media().microphone();
                caller.addTrack(microphone);
                sending = true;

                System.out.println("Sending the microphone");
            }

            if (!sending) {
                System.out.println("There is nothing to send. The ClientApiExample needs no devices.");
                return;
            }

            // Adding the tracks negotiated the call. The main thread may wait; a handler must not.
            caller.whenConnected().get(15, TimeUnit.SECONDS);

            caller.onStats(Duration.ofSeconds(5), stats -> System.out.printf(
                    "Caller sends %d kbit/s over a %s candidate%n",
                    Math.max(0, stats.outgoingBitrate()) / 1000, stats.candidateType()));

            System.out.println("Press Enter to end the call...");
            System.in.read();
        }
        // Closing the runtime closed the peers, the camera and the microphone.

        System.out.println("Closed");
    }

    /**
     * Logs what the callee receives, once a second.
     */
    private static void receive(RemoteTrack track) {
        String kind = track.kind().name().toLowerCase();

        System.out.println("Callee receives the " + kind + " track");

        if (track.isVideo()) {
            AtomicInteger frames = new AtomicInteger();
            AtomicLong lastLog = new AtomicLong(System.nanoTime());

            // The frame is released after the handler returns. A slow handler gets the newest
            // frame next, and the frames in between are dropped.
            track.asVideo().onFrame(frame -> {
                int count = frames.incrementAndGet();
                long now = System.nanoTime();

                if (now - lastLog.get() >= TimeUnit.SECONDS.toNanos(1)) {
                    System.out.printf("Received %d video frames, the last one %dx%d%n", count,
                            frame.buffer.getWidth(), frame.buffer.getHeight());

                    frames.set(0);
                    lastLog.set(now);
                }
            });
        }
        else {
            // The callee would play the microphone back to the speakers, and the microphone
            // would pick it up again. Muting the track keeps its audio for the handler.
            track.asAudio().setVolume(0);

            AtomicLong millis = new AtomicLong();

            track.asAudio().onAudio(chunk -> {
                long total = millis.addAndGet(chunk.frames() * 1000L / chunk.sampleRate());

                if (total % 1000 == 0) {
                    System.out.printf("Received %d s of audio, %d Hz, %d channel(s)%n", total / 1000,
                            chunk.sampleRate(), chunk.channels());
                }
            });
        }

        track.onEnded(() -> System.out.println("The " + kind + " track ended"));
    }
}
