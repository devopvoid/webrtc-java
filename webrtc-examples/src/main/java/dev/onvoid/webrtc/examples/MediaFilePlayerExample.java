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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

import dev.onvoid.webrtc.RTCStats;
import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.Encoding;
import dev.onvoid.webrtc.client.LocalAudio;
import dev.onvoid.webrtc.client.LocalVideo;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.PeerStats;
import dev.onvoid.webrtc.client.RemoteTrack;
import dev.onvoid.webrtc.client.Sender;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.client.swing.VideoView;
import dev.onvoid.webrtc.media.player.MediaFileSource;
import dev.onvoid.webrtc.media.player.MediaInfo;
import dev.onvoid.webrtc.media.player.MediaPlayerListener;
import dev.onvoid.webrtc.media.player.MediaPlayerState;
import dev.onvoid.webrtc.media.recorder.MediaRecorder;
import dev.onvoid.webrtc.media.recorder.MediaRecorderListener;

/**
 * Desktop UI counterpart to {@link MediaFileExample}, built on the client API
 * ({@code dev.onvoid.webrtc.client}): plays a media file, or a live stream
 * from an {@code rtsp://} URL such as an IP camera's, through a pair of peers
 * that live in the same application, shows the received video while the
 * received audio plays on the speakers, and records what is received.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Send the sources of a MediaFileSource as tracks of the client API</li>
 *   <li>Connect two peers in one application through a loopback signaling</li>
 *   <li>Render received video in the Swing {@link VideoView}</li>
 *   <li>Play received audio through the default playout device</li>
 *   <li>Run the peers' handlers on the Swing event dispatch thread</li>
 *   <li>Show what the file contains, where playback is, and what arrives at
 *       the receiver, read from its statistics</li>
 *   <li>Record the received call with a MediaRecorder</li>
 *   <li>Start and stop a session repeatedly, releasing everything in order</li>
 * </ul>
 * <p>
 * One runtime serves both peers. Its {@link AudioMode#CUSTOM} audio mode sends
 * the file's pushed audio and plays the received audio, which is what two
 * factories did before the client API had audio modes.
 * <p>
 * Audio and video stay in sync because both tracks are sent in the same media
 * stream: the receiver lines them up with each other before handing them on,
 * so the view only has to draw each frame as it arrives.
 * <p>
 * Run it with an optional media file or stream URL to preselect:
 * <pre>
 * java dev.onvoid.webrtc.examples.MediaFilePlayerExample movie.mp4
 * java dev.onvoid.webrtc.examples.MediaFilePlayerExample rtsp://camera.local/stream1
 * </pre>
 * A live stream has no length, so the Loop box is disabled while one plays.
 *
 * @author Alex Andres
 */
public class MediaFilePlayerExample {

    private static final Logger LOG = Logger.getLogger(MediaFilePlayerExample.class.getName());

    /** Both tracks go in this stream, which is what ties them together for lip sync. */
    private static final String STREAM_ID = "media-file";

    /**
     * The most the video may be sent with. Without a limit of its own, WebRTC
     * holds video to about 2 Mbit/s, which fits a call but leaves a 1080p file
     * at a fraction of its resolution. The two peers here share a machine, so
     * there is room for more; the bandwidth estimate still decides how much of
     * it is actually used.
     */
    private static final int MAX_VIDEO_BITRATE = 10_000_000;

    private static final DateTimeFormatter RECORDING_NAME = DateTimeFormatter.ofPattern("'recording-'yyyyMMdd-HHmmss'.mkv'");

    /** Owns the peers and tracks of every session; its handlers run on the EDT. */
    private final WebRtc rtc;

    /** Setting up and tearing down a session blocks, so it stays off the EDT. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "media-session");
        thread.setDaemon(true);
        return thread;
    });

    private final JFrame frame = new JFrame("Media Player");
    private final JTextField fileField = new JTextField(32);
    private final JButton browseButton = new JButton("Browse...");
    private final JButton startButton = new JButton("Start");
    private final JButton stopButton = new JButton("Stop");
    private final JToggleButton recordButton = new JToggleButton("Record");
    private final JCheckBox loopBox = new JCheckBox("Loop");
    private final JLabel statusLabel = new JLabel("Idle");
    private final JProgressBar timeBar = new JProgressBar(0, 1000);
    private final VideoView videoView = new VideoView();
    private final MetricsPanel metricsPanel = new MetricsPanel();
    private final Timer statusTimer = new Timer(250, e -> updateStatus());

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
     * A short name for a source: a file's name, or a stream URL without the
     * credentials it may carry.
     */
    private static String sourceName(String source) {
        return source.contains("://") ? withoutCredentials(source) : new File(source).getName();
    }

    /**
     * The given source without the user name and password a URL can carry in
     * front of its host, so that it can be shown. Anything else, a file path
     * included, is returned as it is.
     */
    private static String withoutCredentials(String source) {
        // Everything between the scheme and the last @ before the path. Not
        // parsed as a URI: a password is free to hold characters a URI
        // parser rejects, and then it would be shown after all.
        return source.replaceFirst("://[^/?#]*@", "://");
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
        // Pushed audio out, played audio in, and every handler on the EDT.
        rtc = WebRtc.builder()
                .audio(AudioMode.CUSTOM)
                .eventExecutor(SwingUtilities::invokeLater)
                .build();

        videoView.setPreferredSize(new Dimension(960, 540));
    }

    private void show(String file) {
        fileField.setText(file);
        fileField.setToolTipText("A media file, or an rtsp:// stream URL");

        browseButton.addActionListener(e -> chooseFile());
        startButton.addActionListener(e -> start());
        stopButton.addActionListener(e -> stop());
        recordButton.addActionListener(e -> record(recordButton.isSelected()));
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
        controls.add(recordButton);
        controls.add(loopBox);

        timeBar.setStringPainted(true);
        timeBar.setString("");

        JPanel statusBar = new JPanel(new BorderLayout(12, 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(timeBar, BorderLayout.CENTER);

        frame.setLayout(new BorderLayout());
        frame.add(controls, BorderLayout.NORTH);
        frame.add(videoView, BorderLayout.CENTER);
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
            statusLabel.setText("Choose a media file or enter an rtsp:// URL first.");
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

                SwingUtilities.invokeLater(() -> {
                    metricsPanel.showMedia(file, started.info);

                    // Only a source with a length can start over.
                    loopBox.setEnabled(started.info.getDurationUs() > 0);
                });
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
        recordButton.setEnabled(false);

        worker.execute(() -> {
            closeSession();

            SwingUtilities.invokeLater(() -> {
                setRunning(false);
                videoView.clear();
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

    /**
     * Starts or stops recording what the receiver gets. Starting and stopping
     * a recorder waits on the peers and on the file, so it runs on the worker.
     */
    private void record(boolean on) {
        worker.execute(() -> {
            Session current = session;
            String message;

            if (current == null) {
                message = "Start playback to record it";
            }
            else if (on) {
                message = current.startRecording();
            }
            else {
                message = current.stopRecording();
            }

            SwingUtilities.invokeLater(() -> {
                statusLabel.setText(message);
                recordButton.setSelected(session != null && session.recorder != null);
            });
        });
    }

    private void exit() {
        statusTimer.stop();
        frame.dispose();

        // Queued behind anything still running, so a session being set up
        // is closed rather than left behind.
        worker.execute(() -> {
            closeSession();
            rtc.close();

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
        recordButton.setEnabled(running);
        fileField.setEnabled(!running);
        browseButton.setEnabled(!running);

        if (!running) {
            loopBox.setEnabled(true);
            recordButton.setSelected(false);
            statusTimer.stop();
        }
        else {
            statusTimer.start();
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

        if (current.recorder == null) {
            statusLabel.setText(state.toString());
        }
        timeBar.setValue(durationUs > 0 ? (int) Math.min(1000, positionUs * 1000 / durationUs) : 0);
        timeBar.setString(time);

        metricsPanel.set("play.state", state.toString());
        metricsPanel.set("play.position", time);
        metricsPanel.set("play.connection", current.receiver.state().toString());
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
     * receiving peers, the tracks over the file's sources, and a recording.
     */
    private class Session {

        final MediaFileSource source;
        final MediaInfo info;

        final Peer sender;
        final Peer receiver;

        private final LocalVideo videoTrack;
        private final LocalAudio audioTrack;

        /** What the receiver gets, kept for recording. Set on the EDT. */
        private volatile RemoteTrack remoteVideo;
        private volatile RemoteTrack remoteAudio;

        /** The recording running, if any. Only touched on the worker. */
        volatile MediaRecorder recorder;
        private Path recording;

        /** Turn the byte counters of successive stats reports into bitrates. */
        private final RateMeter videoRate = new RateMeter();
        private final RateMeter audioRate = new RateMeter();


        Session(String file, boolean looping) throws Exception {
            source = new MediaFileSource(file);
            info = source.getInfo();

            // The peers find each other in process; across a network the
            // messages would go over a WebSocket instead.
            SignalingPair link = Signaling.loopback();
            sender = rtc.createPeer(PeerOptions.builder().name("sender").signaling(link.first()).build());
            receiver = rtc.createPeer(PeerOptions.builder().name("receiver").signaling(link.second())
                    .polite(true).build());

            receiver.onStateChange(state -> LOG.info("Receiver connection: " + state));
            receiver.onTrack(this::onRemoteTrack);
            receiver.onStats(Duration.ofSeconds(1), this::showStats);

            // A source with no video has no video source, and likewise for
            // audio, so each track is only made if there is something to feed
            // it. The tracks wrap the file's sources, which stay the file's.
            if (source.getVideoSource() != null) {
                videoTrack = rtc.media().videoTrack("video", source.getVideoSource());
                Sender videoSender = sender.addTrack(videoTrack, STREAM_ID);

                // Encodings exist once negotiation set the sender up.
                sender.whenConnected()
                        .thenCompose(connected -> videoSender.configure(
                                Encoding.builder().maxBitrate(MAX_VIDEO_BITRATE).build()))
                        .exceptionally(error -> {
                            LOG.log(Level.WARNING, "Could not raise the video bitrate", error);
                            return null;
                        });
            }
            else {
                videoTrack = null;
            }

            if (source.getAudioSource() != null) {
                audioTrack = rtc.media().audioTrack("audio", source.getAudioSource());
                sender.addTrack(audioTrack, STREAM_ID);
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

            // Frames are sent in real time from the moment playback starts,
            // and whatever goes out before the connection is up is dropped.
            // Starting right away keeps the example short; an application
            // that must not lose the first second would wait for
            // receiver.whenConnected().
            source.play();
        }

        /** On the EDT: shows the video, and keeps both tracks for recording. */
        private void onRemoteTrack(RemoteTrack track) {
            if (track.isVideo()) {
                remoteVideo = track;
                track.asVideo().onFrame(videoView);
            }
            else {
                // Nothing to do for playback: the runtime plays received audio.
                remoteAudio = track;
            }
        }

        /** On the EDT, once a second. */
        private void showStats(PeerStats stats) {
            if (session == this) {
                metricsPanel.setAll(readStats(stats.report()));
            }
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

        /** On the worker. */
        String startRecording() {
            if (recorder != null) {
                return "Recording to " + recording;
            }
            if (remoteVideo == null && remoteAudio == null) {
                return "Nothing received yet to record";
            }

            Path file = Paths.get(LocalDateTime.now().format(RECORDING_NAME)).toAbsolutePath();

            try {
                MediaRecorder started = new MediaRecorder(file);
                started.setListener(new MediaRecorderListener() {

                    @Override
                    public void onWarning(String message) {
                        LOG.warning("Recording: " + message);
                    }

                    @Override
                    public void onError(String message) {
                        LOG.warning("Recording failed: " + message);
                    }
                });

                // The receiver's tracks as they arrived, before decoding.
                if (remoteVideo != null) {
                    started.addTrack(remoteVideo);
                }
                if (remoteAudio != null) {
                    started.addTrack(remoteAudio);
                }

                started.start();

                recorder = started;
                recording = file;

                return "Recording to " + file;
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Could not start recording", e);
                return "Could not record: " + e.getMessage();
            }
        }

        /** On the worker. */
        String stopRecording() {
            MediaRecorder running = recorder;
            recorder = null;

            if (running == null) {
                return "Not recording";
            }

            boolean recorded = running.stop();
            running.close();

            return recorded ? "Saved " + recording : "Nothing was recorded";
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

        /** On the worker. */
        void close() {
            if (recorder != null) {
                LOG.info(stopRecording());
            }

            try {
                // The peers go first, then the tracks, which must be released
                // before the file closes the sources they were made over.
                sender.closeAsync().get(5, TimeUnit.SECONDS);
                receiver.closeAsync().get(5, TimeUnit.SECONDS);

                if (videoTrack != null) {
                    videoTrack.closeAsync().get(5, TimeUnit.SECONDS);
                }
                if (audioTrack != null) {
                    audioTrack.closeAsync().get(5, TimeUnit.SECONDS);
                }
            }
            catch (Exception e) {
                LOG.log(Level.WARNING, "Closing the session took too long", e);
            }

            source.close();
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
            addRow("media.file", "Source");
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
            set("media.file", sourceName(file));
            // Never the source as typed: a stream URL may carry a password.
            values.get("media.file").setToolTipText(withoutCredentials(file));
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
}
