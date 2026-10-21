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

package dev.onvoid.webrtc.examples.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.logging.LogManager;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.examples.util.MediaFrameLogger;
import dev.onvoid.webrtc.examples.web.client.SignalingManager;
import dev.onvoid.webrtc.examples.web.media.MediaManager;
import dev.onvoid.webrtc.examples.web.model.JoinMessage;
import dev.onvoid.webrtc.examples.web.model.LeaveMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example demonstrating how to combine WebSocket signaling with the client API
 * ({@code dev.onvoid.webrtc.client}), to call the browser client of the
 * {@link dev.onvoid.webrtc.examples.web.server.WebServer WebServer}.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Connect to a signaling server using WebSockets</li>
 *   <li>Implement a {@code Signaling} over a message format of the application's own</li>
 *   <li>Send generated audio and video, pushed into tracks of the runtime</li>
 *   <li>Let the peer negotiate by itself, and start a new peer whenever the browser joins</li>
 *   <li>Log the audio and video the browser sends</li>
 * </ul>
 * <p>
 * Negotiation, the threads of the callbacks and the release of native objects are
 * left to the client API. The browser only ever answers, so the Java side keeps
 * the default, impolite role of perfect negotiation.
 *
 * @author Alex Andres
 */
public final class WebClientExample implements AutoCloseable {

    static {
        setupLogging();
    }

    private static final Logger LOG = LoggerFactory.getLogger(WebClientExample.class);

    /** Owns the factory, the tracks and the peers. */
    private final WebRtc rtc;

    /** Manages communication with the signaling server. */
    private final SignalingManager signaling;

    /** Creates and feeds the audio and video tracks. */
    private final MediaManager mediaManager;

    /** The connection to the browser, replaced whenever the browser joins anew. */
    private Peer peer;


    /**
     * Creates a new WebClientExample that combines WebSocket signaling with WebRTC.
     *
     * @param signalingUri The URI of the signaling server.
     * @param subprotocol  The WebSocket subprotocol to use.
     */
    public WebClientExample(URI signalingUri, String subprotocol) {
        // Sends generated audio, and plays what the browser sends to the speakers.
        rtc = WebRtc.builder().audio(AudioMode.CUSTOM).build();

        mediaManager = new MediaManager(rtc);

        signaling = new SignalingManager(signalingUri, subprotocol);
        signaling.setUserId("java-client");
        signaling.setOnJoinReceived(this::handleJoin);
        signaling.setOnLeaveReceived(this::handleLeave);

        LOG.info("WebClientExample created with audio and video tracks");
    }

    /**
     * Connects to the signaling server.
     *
     * @return true if the connection was successful, false otherwise.
     */
    public boolean connect() {
        return signaling.connect();
    }

    /**
     * Joins a room on the signaling server.
     *
     * @param roomName the name of the room to join on the signaling server
     */
    public void joinRoom(String roomName) {
        if (!signaling.isConnected()) {
            LOG.warn("Cannot join room: not connected to signaling server");
            return;
        }

        signaling.sendJoin(roomName);
    }

    /**
     * Calls the browser with a new peer, which sends its offer by itself once its tracks
     * are added. A peer that was there before is closed.
     */
    public synchronized void call() {
        if (!signaling.isConnected()) {
            LOG.warn("Cannot initiate call: not connected to signaling server");
            return;
        }

        if (peer != null) {
            peer.close();
        }

        // The new peer attaches to the signaling, so the browser's messages go to it.
        peer = rtc.createPeer(PeerOptions.builder()
                .name("web-client")
                .iceServer("stun:stun.l.google.com:19302")
                .signaling(signaling)
                .build());

        // The tracks share one stream, so the browser plays them in sync.
        peer.addTrack(mediaManager.audio());
        peer.addTrack(mediaManager.video());

        peer.onStateChange(state -> LOG.info("Connection to the browser is {}", state));
        peer.onTrack(this::handleTrackReceived);
        peer.onError(error -> LOG.warn("The connection to the browser reported an error", error));
    }

    /**
     * Hangs up and disconnects from the signaling server. The runtime closes the peer and
     * the tracks, in the order they need.
     */
    @Override
    public void close() {
        signaling.disconnect();
        mediaManager.close();
        rtc.close();

        LOG.info("Disconnected from signaling server and closed the peer connection");
    }

    /**
     * Logs a track the browser sends. Runs on the peer's event thread, the frames and the
     * audio on threads of their own.
     *
     * @param track The received media track.
     */
    private void handleTrackReceived(RemoteTrack track) {
        LOG.info("Received {} track", track.kind());

        if (track.isAudio()) {
            track.asAudio().onAudio(MediaFrameLogger.createAudioLogger());
        }
        else {
            track.asVideo().onFrame(MediaFrameLogger.createVideoLogger());
        }

        track.onEnded(() -> LOG.info("The browser stopped sending its {} track", track.kind()));
    }

    /**
     * Handles a join message: a browser that joins the room gets a new peer and with it a
     * fresh offer, since it has no connection to answer the earlier one with.
     *
     * @param message The join message.
     */
    private void handleJoin(JoinMessage message) {
        LOG.info("Received join message from peer: {}", message.getFrom());

        call();
    }

    /**
     * Handles a leave message: the browser is gone, so its connection is closed.
     *
     * @param message The leave message.
     */
    private synchronized void handleLeave(LeaveMessage message) {
        LOG.info("Peer {} has left the room", message.getFrom());

        if (peer != null) {
            peer.close();
            peer = null;
        }
    }

    /**
     * Entry point to demonstrate the WebClientExample.
     */
    public static void main(String[] args) {
        try (WebClientExample client = new WebClientExample(URI.create("wss://localhost:8443/ws"), "ws-signaling")) {
            if (client.connect()) {
                LOG.info("Connected to signaling server");

                // Join a room, then call whoever is in it already.
                client.joinRoom("default-room");
                client.call();

                // Keep the application running to observe state changes.
                System.out.println("Press Enter to exit...");
                System.in.read();
            }
            else {
                LOG.error("Failed to connect to signaling server");
            }
        }
        catch (Exception e) {
            LOG.error("Error in WebClientExample", e);
        }
    }

    /**
     * Sets up logging for the application.
     */
    public static void setupLogging() {
        try {
            InputStream configFile = WebClientExample.class.getResourceAsStream("/resources/logging.properties");
            LogManager.getLogManager().readConfiguration(configFile);
        }
        catch (IOException e) {
            System.err.println("Could not load configuration file:");
            System.err.println(e.getMessage());
        }
    }
}
