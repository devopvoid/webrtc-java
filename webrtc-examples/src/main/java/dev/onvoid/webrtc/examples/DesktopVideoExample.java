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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.Devices;
import dev.onvoid.webrtc.client.LocalVideo;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.ScreenOptions;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.VideoStats;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;

/**
 * Example demonstrating how to share a screen or a window over a peer connection, with the
 * client API.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>List the screens and windows that can be captured</li>
 *   <li>Capture one of them as a local video track, with a frame rate and a largest size</li>
 *   <li>Connect two peers through a loopback signaling, in place of a server</li>
 *   <li>Send the track, and receive it on the other peer</li>
 *   <li>Read the resolution and frame rate the sender encodes</li>
 * </ul>
 * <p>
 * Both peers run in this process, so the call connects without a server. In a real
 * application each peer is in another process, and its {@link Signaling} carries the messages
 * over a WebSocket or the like.
 * <p>
 * Under Wayland, the system asks which screen or window to share once the capture starts, and
 * each list holds a single source without a title that stands for that choice.
 * <p>
 * Run it to share the first screen, or with {@code window} to share the first window, and press
 * Enter to stop:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.DesktopVideoExample"
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.DesktopVideoExample" -D"exec.args=window"
 * </pre>
 *
 * @author Alex Andres
 */
public class DesktopVideoExample {

    public static void main(String[] args) throws Exception {
        boolean shareWindow = args.length > 0 && args[0].equals("window");

        // Only video is sent, so the runtime needs no audio devices.
        try (WebRtc rtc = WebRtc.builder().audio(AudioMode.HEADLESS).build()) {
            // Screens and windows are read from the system on every call.
            Devices devices = rtc.media().devices();
            List<DesktopSource> screens = devices.screens();
            List<DesktopSource> windows = devices.windows();

            System.out.println("Available screens:");
            screens.forEach(screen -> System.out.println("  " + describe(screen)));

            System.out.println("Available windows:");
            windows.forEach(window -> System.out.println("  " + describe(window)));

            List<DesktopSource> sources = shareWindow ? windows : screens;

            if (sources.isEmpty()) {
                System.out.println("There is no " + (shareWindow ? "window" : "screen") + " to share");
                return;
            }

            DesktopSource source = sources.get(0);
            ScreenOptions.Builder options = shareWindow
                    ? ScreenOptions.window(source)
                    : ScreenOptions.screen(source);

            // Frames larger than 1920x1080 are scaled down, keeping their aspect ratio.
            LocalVideo screen = rtc.media().screen(options.frameRate(30).maxSize(1920, 1080).build());

            System.out.println("Sharing " + describe(source));

            // The two ends of a loopback signaling hand each message to the other peer.
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

            viewer.onTrack(track -> {
                AtomicInteger frames = new AtomicInteger();

                track.asVideo().onFrame(frame -> {
                    if (frames.incrementAndGet() % 30 == 1) {
                        System.out.printf("Viewer got frame %d, %dx%d%n", frames.get(),
                                frame.buffer.getWidth(), frame.buffer.getHeight());
                    }
                });
            });

            sharer.addTrack(screen);

            // The main thread may wait; a handler must not.
            sharer.whenConnected().get(15, TimeUnit.SECONDS);

            sharer.onStats(Duration.ofSeconds(5), stats -> {
                VideoStats video = stats.outboundVideo().isEmpty() ? null : stats.outboundVideo().get(0);

                // Nothing is encoded before the first frame was captured.
                if (video != null && video.width() > 0) {
                    System.out.printf("Sharer encodes %s %dx%d at %.0f fps, %d kbit/s%n", video.codec(),
                            video.width(), video.height(), video.framesPerSecond(),
                            Math.max(0, stats.outgoingBitrate()) / 1000);
                }
            });

            System.out.println("Press Enter to stop sharing...");
            System.in.read();
        }
        // Closing the runtime closed the peers and stopped the capture.

        System.out.println("Closed");
    }

    private static String describe(DesktopSource source) {
        String title = source.title == null || source.title.isEmpty() ? "(chosen by the system)" : source.title;

        return title + " (ID: " + source.id + ")";
    }
}
