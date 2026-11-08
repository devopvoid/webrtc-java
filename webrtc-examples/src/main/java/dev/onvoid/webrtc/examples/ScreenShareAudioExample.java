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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.onvoid.webrtc.client.AudioChunk;
import dev.onvoid.webrtc.client.LocalAudio;
import dev.onvoid.webrtc.client.LocalVideo;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.RemoteAudio;
import dev.onvoid.webrtc.client.ScreenOptions;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.media.audio.SystemAudioSource;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;

/**
 * Example demonstrating how to share a screen together with the microphone and
 * the audio the system plays, from one runtime and over one peer, with the
 * client API.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Capture a screen, the microphone and the system output as three tracks</li>
 *   <li>Send the system output through a {@link SystemAudioSource}, beside the microphone</li>
 *   <li>Put all three tracks into one stream, so the receiver keeps them in sync</li>
 *   <li>Tell the received audio tracks apart by their IDs</li>
 *   <li>Stop the system audio capture and check it for a failure</li>
 * </ul>
 * <p>
 * Each audio sender gets the audio of its own track: the microphone, with echo
 * cancellation, noise suppression and gain control, on one, and the system
 * output, unprocessed, on the other. Both peers run in this process, so the
 * call connects without a server. The viewer does not play what it receives:
 * on Windows and Linux the system audio capture would record that playout and
 * send it again.
 * <p>
 * Play audio in another application, speak into the microphone, run the
 * example and press Enter to stop. On macOS, grant screen and audio recording
 * permission when asked:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.ScreenShareAudioExample"
 * </pre>
 *
 * @author Alex Andres
 */
public class ScreenShareAudioExample {

    /** A 16-bit sample above this is counted as sound rather than silence. */
    private static final int AUDIBLE = 300;


    public static void main(String[] args) throws Exception {
        // AudioMode.DEVICES, the default: the microphone is captured, and pushed
        // audio such as the system output can be sent beside it.
        WebRtc rtc = WebRtc.create();
        SystemAudioSource systemOutput = null;

        try {
            List<DesktopSource> screens = rtc.media().devices().screens();

            if (screens.isEmpty()) {
                System.out.println("There is no screen to share");
                return;
            }

            DesktopSource source = screens.get(0);
            LocalVideo screen = rtc.media().screen(ScreenOptions.screen(source)
                    .frameRate(30)
                    .maxSize(1920, 1080)
                    .build());
            LocalAudio microphone = rtc.media().microphone();

            // The source stays the application's: it must outlive its track,
            // which the runtime closes.
            systemOutput = new SystemAudioSource();
            LocalAudio systemAudio = rtc.media().audioTrack("system-audio", systemOutput);

            SignalingPair signaling = Signaling.loopback();

            Peer sharer = rtc.createPeer(PeerOptions.builder()
                    .name("sharer")
                    .signaling(signaling.first())
                    .build());
            Peer viewer = rtc.createPeer(PeerOptions.builder()
                    .name("viewer")
                    .polite(true)
                    .signaling(signaling.second())
                    .build());

            AtomicInteger frames = new AtomicInteger();
            Map<String, AtomicLong> audibleChunks = new ConcurrentHashMap<>();

            viewer.onTrack(track -> {
                if (track.isVideo()) {
                    track.asVideo().onFrame(frame -> frames.incrementAndGet());
                    return;
                }

                String name = track.id().equals(systemAudio.id()) ? "system audio" : "microphone";
                AtomicLong audible = audibleChunks.computeIfAbsent(name, key -> new AtomicLong());
                RemoteAudio audio = track.asAudio();

                // Count the audio instead of playing it, which the system audio
                // capture would pick up again.
                audio.setVolume(0);
                audio.onAudio(chunk -> {
                    if (isAudible(chunk)) {
                        audible.incrementAndGet();
                    }
                });
            });

            // One stream for all three tracks, so the viewer lines them up.
            sharer.addTrack(screen, "presentation");
            sharer.addTrack(microphone, "presentation");
            sharer.addTrack(systemAudio, "presentation");

            // Waits for the platform to open the output, and on macOS for the
            // permission prompt. An application with a user interface calls it
            // on a background thread.
            systemOutput.start();

            // The main thread may wait; a handler must not.
            sharer.whenConnected().get(15, TimeUnit.SECONDS);

            System.out.println("Sharing " + source.title + " with the microphone and the system audio");

            sharer.onStats(Duration.ofSeconds(5), stats -> {
                System.out.printf("Viewer got %d frames, audible 10 ms chunks: %s%n",
                        frames.get(), audibleChunks);
            });

            System.out.println("Press Enter to stop sharing...");
            System.in.read();

            systemOutput.stop();

            if (systemOutput.getFailure() != null) {
                System.out.println("System audio capture failed: " + systemOutput.getFailure());
            }
        }
        finally {
            // Closing the runtime closed the peers and the tracks, so the system
            // audio source can go now.
            rtc.close();

            if (systemOutput != null) {
                systemOutput.close();
            }
        }

        System.out.println("Closed");
    }

    private static boolean isAudible(AudioChunk chunk) {
        byte[] samples = chunk.samples();

        for (int i = 0; i + 1 < samples.length; i += 2) {
            // 16-bit little-endian PCM.
            int sample = (short) ((samples[i] & 0xff) | (samples[i + 1] << 8));

            if (Math.abs(sample) > AUDIBLE) {
                return true;
            }
        }

        return false;
    }
}
