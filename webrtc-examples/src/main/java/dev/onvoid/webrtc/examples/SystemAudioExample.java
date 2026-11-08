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

import java.util.concurrent.atomic.AtomicLong;

import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.SystemAudioSource;

/**
 * Example demonstrating how to capture the audio the system plays, as a local
 * audio track.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Capture the default output with a {@link SystemAudioSource}</li>
 *   <li>Read the captured audio through a sink of its track</li>
 *   <li>Stop the capture and check it for a failure</li>
 * </ul>
 * <p>
 * The captured audio is counted, not played back. To send it, add the track to
 * a peer connection, beside the track of a desktop video source.
 * <p>
 * Play audio in another application, run this example, and press Enter to stop:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.SystemAudioExample"
 * </pre>
 *
 * @author Alex Andres
 */
public class SystemAudioExample {

    public static void main(String[] args) throws Exception {
        // The source opens its own output capture, so no microphone is needed.
        try (AudioDeviceModule audio = new AudioDeviceModule(AudioLayer.kDummyAudio);
             PeerConnectionFactory factory = new PeerConnectionFactory(audio);
             SystemAudioSource source = new SystemAudioSource()) {
            AudioTrack track = factory.createAudioTrack("system-audio", source);
            AtomicLong frames = new AtomicLong();

            try {
                track.addSink((data, bits, rate, channels, count) -> frames.addAndGet(count));

                source.start();

                System.out.println("Capturing system audio. Play audio in another application; press Enter to stop.");
                System.in.read();

                source.stop();

                if (source.getFailure() != null) {
                    throw new IllegalStateException("System audio capture failed", source.getFailure());
                }

                System.out.println("Captured " + frames.get() + " stereo PCM frames");
            }
            finally {
                source.stop();
                track.dispose();
            }
        }
    }

}
