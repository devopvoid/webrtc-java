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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCRtpReceiver;
import dev.onvoid.webrtc.RTCRtpEncodingParameters;
import dev.onvoid.webrtc.RTCRtpSendParameters;
import dev.onvoid.webrtc.RTCRtpSender;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.ffmpeg.MediaFileSource;
import dev.onvoid.webrtc.media.ffmpeg.MediaInfo;
import dev.onvoid.webrtc.media.ffmpeg.MediaPlayerListener;
import dev.onvoid.webrtc.media.ffmpeg.MediaPlayerState;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;

/**
 * Desktop UI counterpart to {@link MediaFileExample}: plays a media file
 * through a pair of peer connections that live in the same application, and
 * shows the received video while the received audio plays on the speakers.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Send a MediaFileSource over one peer connection</li>
 *   <li>Connect it to a second, receiving peer connection without a
 *       signaling server, by handing descriptions and candidates across
 *       directly</li>
 *   <li>Render received video frames in a Swing component</li>
 *   <li>Play received audio through the default playout device</li>
 *   <li>Show what the file contains, where playback is, and what arrives
 *       at the receiver, read from its statistics</li>
 *   <li>Start and stop a session repeatedly, releasing everything in
 *       order</li>
 * </ul>
 * <p>
 * The two sides use two factories. The sending factory is fed pushed audio,
 * so it gets a dummy audio layer. The receiving factory gets the platform's
 * audio layer, whose playout is what makes the received audio audible.
 * <p>
 * Audio and video stay in sync because both tracks are sent in the same
 * media stream: the receiver lines them up with each other before handing
 * them on, so the video sink only has to draw each frame as it arrives.
 * <p>
 * Run it with an optional media file to preselect:
 * <pre>
 * java dev.onvoid.webrtc.examples.MediaFilePlayerExample movie.mp4
 * </pre>
 *
 * @author Alex Andres
 */
public class MediaFilePlayerExample {

    private static final Logger LOG = Logger.getLogger(MediaFilePlayerExample.class.getName());

    /** Both tracks go in this stream, which is what ties them together for lip sync. */
    private static final List<String> STREAM_IDS = List.of("media-file");

    /**
     * The most the video may be sent with. Without a limit of its own, WebRTC
     * holds video to about 2 Mbit/s, which fits a call but leaves a 1080p file
     * at a fraction of its resolution. The two peers here share a machine, so
     * there is room for more; the bandwidth estimate still decides how much of
     * it is actually used.
     */
    private static final int MAX_VIDEO_BITRATE = 10_000_000;

    private final AudioDeviceModule senderAudioModule;
    private final PeerConnectionFactory senderFactory;

    private final AudioDeviceModule receiverAudioModule;
    private final PeerConnectionFactory receiverFactory;

    /** Setting up and tearing down a session blocks, so it stays off the EDT. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "media-session");
        thread.setDaemon(true);
        return thread;
    });

    private final JFrame frame = new JFrame("Media File Player");
    private final JTextField fileField = new JTextField(32);
    private final JButton browseButton = new JButton("Browse...");
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JCheckBox loopBox = new JCheckBox("Loop");
    private final JLabel statusLabel = new JLabel("Idle");
    private final JProgressBar timeBar = new JProgressBar(0, 1000);
    private final VideoPanel videoPanel = new VideoPanel();
    private final MetricsPanel metricsPanel = new MetricsPanel();
    private final Timer statusTimer = new Timer(250, e -> updateStatus());
    private final Timer statsTimer = new Timer(1000, e -> requestStats());

    /** Only touched from the worker thread and read on the EDT for status. */
    private volatile Session session;


    public static void main(String[] args) {
        String file = args.length > 0 ? args[0] : "";

        SwingUtilities.invokeLater(() -> {
            usePlatformLookAndFeel();

            new MediaFilePlayerExample().show(file);
        });
    }

    /**
     * Makes the window look like the platform's own applications rather than
     * Swing's cross-platform default. It has to happen before the first
     * component is created, which only picks up the look it was made with.
     */
    private static void usePlatformLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        }
        catch (Exception e) {
            // The default look works everywhere, so this is only cosmetic.
            LOG.log(Level.WARNING, "Could not use the platform look and feel", e);
        }
    }

    private MediaFilePlayerExample() {
        senderAudioModule = new AudioDeviceModule(AudioLayer.kDummyAudio);
        senderFactory = new PeerConnectionFactory(senderAudioModule);

        receiverAudioModule = new AudioDeviceModule();
        receiverFactory = new PeerConnectionFactory(receiverAudioModule);
    }

    private void show(String file) {
        fileField.setText(file);

        browseButton.addActionListener(e -> chooseFile());
        startButton.addActionListener(e -> start());
        stopButton.addActionListener(e -> stop());
        loopBox.addActionListener(e -> {
            Session current = session;
            if (current != null) {
                current.source.setLooping(loopBox.isSelected());
            }
        });

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(fileField);
        controls.add(browseButton);
        controls.add(startButton);
        controls.add(stopButton);
        controls.add(loopBox);

        timeBar.setStringPainted(true);
        timeBar.setString("");

        JPanel statusBar = new JPanel(new BorderLayout(12, 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(timeBar, BorderLayout.CENTER);

        frame.setLayout(new BorderLayout());
        frame.add(controls, BorderLayout.NORTH);
        frame.add(videoPanel, BorderLayout.CENTER);
        frame.add(metricsPanel, BorderLayout.EAST);
        frame.add(statusBar, BorderLayout.SOUTH);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });

        setRunning(false);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();

        String current = fileField.getText().trim();
        if (!current.isEmpty()) {
            chooser.setSelectedFile(new File(current));
        }

        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            fileField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void start() {
        String file = fileField.getText().trim();
        if (file.isEmpty()) {
            statusLabel.setText("Choose a media file first.");
            return;
        }

        boolean looping = loopBox.isSelected();

        setRunning(true);
        statusLabel.setText("Connecting...");
        metricsPanel.clear("");

        worker.execute(() -> {
            try {
                Session started = new Session(file, looping);
                session = started;

                SwingUtilities.invokeLater(() -> metricsPanel.showMedia(file, started.info));
            }
            catch (Exception e) {
                LOG.log(Level.SEVERE, "Could not start playback", e);

                SwingUtilities.invokeLater(() -> {
                    setRunning(false);
                    statusLabel.setText("Failed: " + e.getMessage());
                });
            }
        });
    }

    private void stop() {
        startButton.setEnabled(false);
        stopButton.setEnabled(false);

        worker.execute(() -> {
            closeSession();

            SwingUtilities.invokeLater(() -> {
                setRunning(false);
                videoPanel.clear();
                statusLabel.setText("Stopped");
                timeBar.setValue(0);
                timeBar.setString("");

                // What the file contains stays up, what was playing does not.
                metricsPanel.clear("play.");
                metricsPanel.clear("video.");
                metricsPanel.clear("audio.");
            });
        });
    }

    private void exit() {
        statusTimer.stop();
        statsTimer.stop();
        frame.dispose();

        // Queued behind anything still running, so a session being set up
        // is closed rather than left behind.
        worker.execute(() -> {
            closeSession();

            receiverFactory.dispose();
            receiverAudioModule.dispose();
            senderFactory.dispose();
            senderAudioModule.dispose();

            System.exit(0);
        });
    }

    private void closeSession() {
        Session current = session;
        session = null;

        if (current != null) {
            current.close();
        }
    }

    private void setRunning(boolean running) {
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        fileField.setEnabled(!running);
        browseButton.setEnabled(!running);

        if (running) {
            statusTimer.start();
            statsTimer.start();
        }
        else {
            statusTimer.stop();
            statsTimer.stop();
        }
    }

    private void updateStatus() {
        Session current = session;
        if (current == null) {
            return;
        }

        MediaPlayerState state = current.source.getState();
        long positionUs = current.source.getPositionUs();
        long durationUs = current.info.getDurationUs();

        // Live streams and some containers have no duration to measure against.
        String time = durationUs > 0
                ? formatTime(positionUs) + " / " + formatTime(durationUs)
                : formatTime(positionUs);

        statusLabel.setText(state.toString());
        timeBar.setValue(durationUs > 0 ? (int) Math.min(1000, positionUs * 1000 / durationUs) : 0);
        timeBar.setString(time);

        metricsPanel.set("play.state", state.toString());
        metricsPanel.set("play.position", time);
        metricsPanel.set("play.connection", current.connectionState.toString());
    }

    /**
     * Asks the current session for its statistics. This goes through the
     * worker, which is where sessions are closed, so a request can never
     * reach a peer connection that is being closed at the same time.
     */
    private void requestStats() {
        worker.execute(() -> {
            Session current = session;
            if (current != null) {
                current.requestStats();
            }
        });
    }

    private static String formatTime(long us) {
        long tenths = us / 100_000;
        long s = tenths / 10;

        if (s >= 3600) {
            return String.format("%d:%02d:%02d.%d", s / 3600, s / 60 % 60, s % 60, tenths % 10);
        }
        return String.format("%d:%02d.%d", s / 60, s % 60, tenths % 10);
    }

    private static String formatBitrate(Double bitsPerSecond) {
        if (bitsPerSecond == null) {
            return null;
        }
        if (bitsPerSecond >= 1_000_000) {
            return String.format("%.2f Mbit/s", bitsPerSecond / 1_000_000);
        }
        return String.format("%.0f kbit/s", bitsPerSecond / 1_000);
    }

    /**
     * Formats a codec entry of a stats report, such as {@code VP8} or
     * {@code opus, 48000 Hz, 2 ch}.
     */
    private static String formatCodec(RTCStats codec) {
        if (codec == null) {
            return null;
        }

        Map<String, Object> attributes = codec.getAttributes();
        String mimeType = String.valueOf(attributes.get("mimeType"));
        String name = mimeType.substring(mimeType.indexOf('/') + 1);

        if (!mimeType.startsWith("audio/")) {
            return name;
        }

        long channels = count(attributes, "channels");

        return String.format("%s, %d Hz, %d ch", name, count(attributes, "clockRate"),
                channels > 0 ? channels : 1);
    }

    private static String formatLoss(Map<String, Object> attributes) {
        long lost = count(attributes, "packetsLost");
        long total = lost + count(attributes, "packetsReceived");

        if (total <= 0) {
            return null;
        }
        return String.format("%d of %d (%.1f %%)", lost, total, lost * 100.0 / total);
    }

    private static String format(String pattern, Double value) {
        return value != null ? String.format(pattern, value) : null;
    }

    /**
     * Reads a counter from stats attributes. Counters come as Integer, Long or
     * BigInteger depending on their native type, and are absent until there is
     * something to count.
     */
    private static long count(Map<String, Object> attributes, String name) {
        Object value = attributes.get(name);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    private static Double decimal(Map<String, Object> attributes, String name) {
        Object value = attributes.get(name);
        return value instanceof Number ? ((Number) value).doubleValue() : null;
    }

    /**
     * One run of the file, from Start to Stop: the source, the sending and
     * receiving peer connections, and what connects them.
     */
    private class Session {

        final MediaFileSource source;
        final MediaInfo info;

        final Peer sender;
        final Peer receiver;

        private final VideoTrack videoTrack;
        private final AudioTrack audioTrack;
        private RTCRtpSender videoSender;
        private final List<RTCRtpSender> rtpSenders = new ArrayList<>();

        volatile RTCPeerConnectionState connectionState = RTCPeerConnectionState.NEW;

        /** The received video track, kept to detach the sink from on close. */
        private volatile VideoTrack remoteVideoTrack;

        /** Turn the byte counters of successive stats reports into bitrates. */
        private final RateMeter videoRate = new RateMeter();
        private final RateMeter audioRate = new RateMeter();


        Session(String file, boolean looping) throws Exception {
            source = new MediaFileSource(file);
            info = source.getInfo();

            sender = new Peer(senderFactory, "sender");
            receiver = new Peer(receiverFactory, "receiver");

            sender.remote = receiver;
            receiver.remote = sender;

            // A source with no video has no video source, and likewise for
            // audio, so each track is only made if there is something to feed
            // it.
            if (source.getVideoSource() != null) {
                videoTrack = senderFactory.createVideoTrack("video", source.getVideoSource());
                videoSender = sender.peerConnection.addTrack(videoTrack, STREAM_IDS);
                rtpSenders.add(videoSender);
            }
            else {
                videoTrack = null;
            }

            if (source.getAudioSource() != null) {
                audioTrack = senderFactory.createAudioTrack("audio", source.getAudioSource());
                rtpSenders.add(sender.peerConnection.addTrack(audioTrack, STREAM_IDS));
            }
            else {
                audioTrack = null;
            }

            source.setLooping(looping);
            source.setListener(new MediaPlayerListener() {

                @Override
                public void onStateChanged(MediaPlayerState state) {
                    LOG.info("Player state: " + state);
                }

                @Override
                public void onEndOfStream() {
                    stopIfCurrent();
                }

                @Override
                public void onError(String message) {
                    LOG.warning("Playback failed: " + message);
                    stopIfCurrent();
                }
            });

            try {
                // The offer and answer go straight across, where an application
                // talking to a real remote peer would send them over signaling.
                RTCSessionDescription offer = sender.createOffer();
                receiver.setRemoteDescription(offer);

                RTCSessionDescription answer = receiver.createAnswer();
                sender.setRemoteDescription(answer);

                if (videoSender != null) {
                    raiseMaxBitrate(videoSender, MAX_VIDEO_BITRATE);
                }
            }
            catch (Exception e) {
                close();
                throw e;
            }

            // Frames are sent in real time from the moment playback starts,
            // and whatever goes out before the connection is up is dropped.
            // Starting right away keeps the example short; an application
            // that must not lose the first second would wait for CONNECTED.
            source.play();
        }

        /**
         * Lets a sender use up to the given bitrate. Parameters are read,
         * changed and written back as a whole, and only have encodings to
         * change once negotiation has set the sender up.
         */
        private void raiseMaxBitrate(RTCRtpSender rtpSender, int bitrate) {
            RTCRtpSendParameters parameters = rtpSender.getParameters();

            for (RTCRtpEncodingParameters encoding : parameters.encodings) {
                encoding.maxBitrate = bitrate;
            }

            rtpSender.setParameters(parameters);
        }

        /**
         * Stops from a player callback. Closing here would wait on the
         * player's own thread, so it goes through the UI like the Stop button,
         * and only if the user has not already moved on to another session.
         */
        private void stopIfCurrent() {
            SwingUtilities.invokeLater(() -> {
                if (session == this) {
                    stop();
                }
            });
        }

        /**
         * Collects the receiving side's statistics and shows them once they
         * arrive, unless the session has ended in the meantime.
         */
        void requestStats() {
            receiver.peerConnection.getStats(report -> {
                Map<String, String> values = readStats(report);

                SwingUtilities.invokeLater(() -> {
                    if (session == this) {
                        metricsPanel.setAll(values);
                    }
                });
            });
        }

        /**
         * Picks the inbound RTP entries out of a stats report, one per
         * received track, and turns them into the rows of the metrics panel.
         */
        private Map<String, String> readStats(RTCStatsReport report) {
            Map<String, RTCStats> all = report.getStats();
            Map<String, String> values = new HashMap<>();

            for (RTCStats stats : all.values()) {
                if (stats.getType() != RTCStatsType.INBOUND_RTP) {
                    continue;
                }

                Map<String, Object> attributes = stats.getAttributes();
                Object codecId = attributes.get("codecId");
                RTCStats codec = codecId != null ? all.get(codecId) : null;
                Double jitter = decimal(attributes, "jitter");
                Double jitterMs = jitter != null ? jitter * 1000 : null;

                if ("video".equals(attributes.get("kind"))) {
                    long width = count(attributes, "frameWidth");
                    long height = count(attributes, "frameHeight");

                    values.put("video.codec", formatCodec(codec));
                    values.put("video.resolution", width > 0 ? width + "x" + height : null);
                    values.put("video.fps", format("%.1f fps", decimal(attributes, "framesPerSecond")));
                    values.put("video.bitrate", formatBitrate(videoRate.update(stats)));
                    values.put("video.frames", String.format("%d decoded, %d dropped",
                            count(attributes, "framesDecoded"), count(attributes, "framesDropped")));
                    values.put("video.lost", formatLoss(attributes));
                    values.put("video.jitter", format("%.1f ms", jitterMs));
                }
                else if ("audio".equals(attributes.get("kind"))) {
                    Double level = decimal(attributes, "audioLevel");
                    long samples = count(attributes, "totalSamplesReceived");
                    long concealed = count(attributes, "concealedSamples");

                    values.put("audio.codec", formatCodec(codec));
                    values.put("audio.bitrate", formatBitrate(audioRate.update(stats)));
                    values.put("audio.level", level == null ? null
                            : level > 0 ? String.format("%.1f dBFS", 20 * Math.log10(level))
                            : "silent");
                    values.put("audio.concealed", samples > 0
                            ? String.format("%.1f %%", concealed * 100.0 / samples) : null);
                    values.put("audio.lost", formatLoss(attributes));
                    values.put("audio.jitter", format("%.1f ms", jitterMs));
                }
            }

            return values;
        }

        void onRemoteTrack(MediaStreamTrack track) {
            if (track instanceof VideoTrack) {
                remoteVideoTrack = (VideoTrack) track;
                remoteVideoTrack.addSink(videoPanel);
            }
            // A received audio track needs nothing: the receiving factory's
            // audio device module mixes it into playout on its own.
        }

        void close() {
            // Stop drawing before anything goes away underneath the sink.
            VideoTrack remoteVideo = remoteVideoTrack;
            if (remoteVideo != null) {
                remoteVideo.removeSink(videoPanel);
            }

            // Senders are not owned by the peer connection. Releasing them
            // before it closes lets go of the tracks they carry.
            for (RTCRtpSender rtpSender : rtpSenders) {
                rtpSender.dispose();
            }

            sender.close();
            receiver.close();

            // The tracks go before the source, which disposes of the media
            // sources they were made from.
            if (videoTrack != null) {
                videoTrack.dispose();
            }
            if (audioTrack != null) {
                audioTrack.dispose();
            }

            source.close();
        }


        /**
         * One end of the in-process connection. Candidates found here are
         * handed to the other end, held back until it has a remote
         * description to match them against.
         */
        private class Peer implements PeerConnectionObserver {

            final RTCPeerConnection peerConnection;

            private final String name;

            private final List<RTCIceCandidate> pendingCandidates = new ArrayList<>();

            private boolean hasRemoteDescription;

            Peer remote;


            Peer(PeerConnectionFactory factory, String name) {
                this.name = name;
                this.peerConnection = factory.createPeerConnection(new RTCConfiguration(), this);
            }

            RTCSessionDescription createOffer() throws Exception {
                CompletableFuture<RTCSessionDescription> offer = new CompletableFuture<>();
                peerConnection.createOffer(new RTCOfferOptions(), createObserver(offer));

                return setLocalDescription(offer.get());
            }

            RTCSessionDescription createAnswer() throws Exception {
                CompletableFuture<RTCSessionDescription> answer = new CompletableFuture<>();
                peerConnection.createAnswer(new RTCAnswerOptions(), createObserver(answer));

                return setLocalDescription(answer.get());
            }

            RTCSessionDescription setLocalDescription(RTCSessionDescription description)
                    throws Exception {
                CompletableFuture<Void> done = new CompletableFuture<>();
                peerConnection.setLocalDescription(description, setObserver(done));
                done.get();

                return description;
            }

            void setRemoteDescription(RTCSessionDescription description) throws Exception {
                CompletableFuture<Void> done = new CompletableFuture<>();
                peerConnection.setRemoteDescription(description, setObserver(done));
                done.get();

                List<RTCIceCandidate> candidates;
                synchronized (this) {
                    hasRemoteDescription = true;
                    candidates = new ArrayList<>(pendingCandidates);
                    pendingCandidates.clear();
                }

                candidates.forEach(peerConnection::addIceCandidate);
            }

            void addRemoteCandidate(RTCIceCandidate candidate) {
                synchronized (this) {
                    if (!hasRemoteDescription) {
                        pendingCandidates.add(candidate);
                        return;
                    }
                }

                peerConnection.addIceCandidate(candidate);
            }

            void close() {
                peerConnection.close();
            }

            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                remote.addRemoteCandidate(candidate);
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                LOG.info(name + " connection state: " + state);

                if (this == receiver) {
                    connectionState = state;
                }
            }

            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {
                RTCRtpReceiver rtpReceiver = transceiver.getReceiver();

                onRemoteTrack(rtpReceiver.getTrack());

                // The receiver and transceiver are query results the
                // application owns. The track itself is unaffected and keeps
                // delivering frames to its sink.
                rtpReceiver.dispose();
                transceiver.dispose();
            }

            private CreateSessionDescriptionObserver createObserver(
                    CompletableFuture<RTCSessionDescription> future) {
                return new CreateSessionDescriptionObserver() {

                    @Override
                    public void onSuccess(RTCSessionDescription description) {
                        future.complete(description);
                    }

                    @Override
                    public void onFailure(String error) {
                        future.completeExceptionally(new Exception(name + ": " + error));
                    }
                };
            }

            private SetSessionDescriptionObserver setObserver(CompletableFuture<Void> future) {
                return new SetSessionDescriptionObserver() {

                    @Override
                    public void onSuccess() {
                        future.complete(null);
                    }

                    @Override
                    public void onFailure(String error) {
                        future.completeExceptionally(new Exception(name + ": " + error));
                    }
                };
            }
        }
    }



    /**
     * Turns a byte counter that only grows into a bitrate, measured over the
     * time between two successive readings of it.
     */
    private static class RateMeter {

        private long lastBytes;

        private long lastTimestampUs;


        /**
         * Takes the next reading from inbound RTP stats.
         *
         * @return The bitrate in bits per second since the last reading, or
         *         {@code null} if there is nothing to compare against yet.
         */
        Double update(RTCStats stats) {
            long bytes = count(stats.getAttributes(), "bytesReceived");
            long timestampUs = stats.getTimestamp();
            Double rate = null;

            if (lastTimestampUs > 0 && timestampUs > lastTimestampUs && bytes >= lastBytes) {
                rate = (bytes - lastBytes) * 8 * 1_000_000.0 / (timestampUs - lastTimestampUs);
            }

            lastBytes = bytes;
            lastTimestampUs = timestampUs;

            return rate;
        }
    }



    /**
     * A column of named values, grouped into sections: what the file
     * contains, where playback is, and what the receiver gets for each track.
     * Each value is addressed by a key such as {@code video.fps}, and shows a
     * dash while there is nothing to show.
     */
    private static class MetricsPanel extends JPanel {

        private static final String NONE = "-";

        private final Map<String, JLabel> values = new LinkedHashMap<>();

        private int row;


        MetricsPanel() {
            super(new GridBagLayout());

            setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            // Fixed, so the video does not shift as the values change width.
            setPreferredSize(new Dimension(320, 0));

            addSection("Media");
            addRow("media.file", "File");
            addRow("media.duration", "Duration");
            addRow("media.video", "Video");
            addRow("media.audio", "Audio");

            addSection("Playback");
            addRow("play.state", "State");
            addRow("play.position", "Position");
            addRow("play.connection", "Connection");

            addSection("Received video");
            addRow("video.codec", "Codec");
            addRow("video.resolution", "Resolution");
            addRow("video.fps", "Frame rate");
            addRow("video.bitrate", "Bitrate");
            addRow("video.frames", "Frames");
            addRow("video.lost", "Packets lost");
            addRow("video.jitter", "Jitter");

            addSection("Received audio");
            addRow("audio.codec", "Codec");
            addRow("audio.bitrate", "Bitrate");
            addRow("audio.level", "Level");
            addRow("audio.concealed", "Concealed");
            addRow("audio.lost", "Packets lost");
            addRow("audio.jitter", "Jitter");

            // Takes up the remaining height, keeping the rows at the top.
            GridBagConstraints filler = new GridBagConstraints();
            filler.gridy = row;
            filler.weighty = 1;
            add(new JPanel(), filler);
        }

        void showMedia(String file, MediaInfo info) {
            set("media.file", new File(file).getName());
            values.get("media.file").setToolTipText(file);
            set("media.duration", info.getDurationUs() > 0
                    ? formatTime(info.getDurationUs()) : "unknown");
            set("media.video", info.hasVideo()
                    ? String.format("%s, %dx%d, %.2f fps", info.getVideoCodec(),
                            info.getVideoWidth(), info.getVideoHeight(), info.getFrameRate())
                    : "none");
            set("media.audio", info.hasAudio()
                    ? String.format("%s, %d Hz, %d ch", info.getAudioCodec(),
                            info.getSampleRate(), info.getChannels())
                    : "none");
        }

        void set(String key, String text) {
            values.get(key).setText(text != null ? text : NONE);
        }

        void setAll(Map<String, String> newValues) {
            newValues.forEach(this::set);
        }

        /**
         * Resets every value whose key starts with the prefix, all of them
         * for an empty one.
         */
        void clear(String prefix) {
            values.forEach((key, label) -> {
                if (key.startsWith(prefix)) {
                    label.setText(NONE);
                    label.setToolTipText(null);
                }
            });
        }

        private void addSection(String title) {
            JLabel label = new JLabel(title);
            label.setFont(label.getFont().deriveFont(Font.BOLD));

            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(row == 0 ? 0 : 12, 0, 4, 0);

            add(label, c);
            row++;
        }

        private void addRow(String key, String name) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(1, 0, 1, 12);

            add(new JLabel(name), c);

            JLabel value = new JLabel(NONE);

            c = new GridBagConstraints();
            c.gridx = 1;
            c.gridy = row;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(1, 0, 1, 0);

            add(value, c);
            values.put(key, value);
            row++;
        }
    }



    /**
     * Draws the most recent received video frame, scaled to fit while keeping
     * its aspect ratio.
     * <p>
     * Frames arrive on a WebRTC thread already timed to match the audio, so
     * they are converted there and only the drawing is left to the EDT.
     */
    private static class VideoPanel extends JPanel implements VideoTrackSink {

        private BufferedImage image;

        private ByteBuffer pixelBuffer;


        VideoPanel() {
            setBackground(Color.BLACK);
            setPreferredSize(new Dimension(960, 540));
        }

        @Override
        public void onVideoFrame(VideoFrame frame) {
            VideoFrameBuffer buffer = frame.buffer;
            int width = buffer.getWidth();
            int height = buffer.getHeight();

            try {
                synchronized (this) {
                    if (image == null || image.getWidth() != width || image.getHeight() != height) {
                        image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                        pixelBuffer = ByteBuffer.allocateDirect(width * height * 4)
                                .order(ByteOrder.LITTLE_ENDIAN);
                    }

                    // libyuv's ARGB is B, G, R, A in memory, which read as
                    // little-endian ints is exactly what TYPE_INT_RGB holds.
                    pixelBuffer.clear();
                    VideoBufferConverter.convertFromI420(buffer, pixelBuffer, FourCC.ARGB);
                    pixelBuffer.rewind();

                    int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                    pixelBuffer.asIntBuffer().get(pixels);
                }
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Could not convert a video frame", e);
            }
            finally {
                frame.release();
            }

            repaint();
        }

        void clear() {
            synchronized (this) {
                image = null;
                pixelBuffer = null;
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            synchronized (this) {
                if (image == null) {
                    return;
                }

                double scale = Math.min((double) getWidth() / image.getWidth(),
                        (double) getHeight() / image.getHeight());
                int w = (int) (image.getWidth() * scale);
                int h = (int) (image.getHeight() * scale);
                int x = (getWidth() - w) / 2;
                int y = (getHeight() - h) / 2;

                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(image, x, y, w, h, null);
            }
        }
    }
}
