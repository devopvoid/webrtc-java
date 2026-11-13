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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import dev.onvoid.webrtc.client.swing.VideoView;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

/**
 * Swing example taking frames from a {@link DesktopCapturer} whose source the
 * user chooses in the operating system's own picker, without a video track.
 * <p>
 * "Choose" starts a {@link ScreenCapturer} set to use the system picker: on
 * macOS 14 and newer, ScreenCaptureKit's content sharing picker, which offers
 * a screen or one or more windows; under Wayland, the desktop portal's
 * picker. The window shows the captured frames and the last capture result.
 * Cancelling the picker, or stopping the sharing from the macOS menu bar, ends
 * the capture with {@link DesktopCapturer.Result#ERROR_PERMANENT}.
 * <p>
 * A capturer is used from one thread only, here a single scheduled thread
 * that starts it, calls {@link DesktopCapturer#captureFrame()} at the frame
 * rate and disposes of it.
 * <p>
 * Run it with:
 * <pre>
 * mvn -pl webrtc-examples exec:java -Dexec.mainClass=dev.onvoid.webrtc.examples.DesktopCapturerPickerExample
 * </pre>
 *
 * @author Alex Andres
 */
public class DesktopCapturerPickerExample {

    private static final int FRAME_RATE = 30;

    private final ScheduledExecutorService captureThread = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "desktop-capture");
        thread.setDaemon(true);
        return thread;
    });

    private final VideoView view = new VideoView();
    private final JLabel status = new JLabel();
    private final JButton chooseButton = new JButton("Choose…");
    private final JButton stopButton = new JButton("Stop");
    private final JFrame window = new JFrame("Desktop Capturer Picker");

    // Used on the capture thread only.
    private DesktopCapturer capturer;
    private DesktopCapturer.Result lastResult;
    private boolean ended;
    private long frames;


    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new DesktopCapturerPickerExample().show());
    }

    private void show() {
        view.setBackground(Color.DARK_GRAY);
        view.setPreferredSize(new Dimension(960, 540));

        status.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        chooseButton.addActionListener(e -> startCapture());
        stopButton.addActionListener(e -> stopCapture("Stopped."));
        stopButton.setEnabled(false);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(chooseButton);
        controls.add(stopButton);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(controls, BorderLayout.WEST);
        bottom.add(status, BorderLayout.CENTER);

        window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        window.addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosed(WindowEvent e) {
                captureThread.execute(() -> disposeCapturer());
                captureThread.shutdown();
            }
        });
        window.getContentPane().add(view, BorderLayout.CENTER);
        window.getContentPane().add(bottom, BorderLayout.SOUTH);
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);

        if (DesktopCapturer.isSystemPickerAvailable()) {
            setStatus("Press \"Choose…\" to pick a screen or window.");
        }
        else {
            chooseButton.setEnabled(false);
            setStatus("This system offers no picker for screens and windows.");
        }

        // Captures while a capturer is running.
        captureThread.scheduleAtFixedRate(this::captureFrame, 0, 1000 / FRAME_RATE, TimeUnit.MILLISECONDS);
    }

    private void startCapture() {
        chooseButton.setEnabled(false);
        stopButton.setEnabled(true);
        view.clear();

        captureThread.execute(() -> {
            disposeCapturer();

            lastResult = null;
            ended = false;
            frames = 0;

            capturer = new ScreenCapturer();
            capturer.setMaxFrameRate(FRAME_RATE);
            // Set before start(). No selectSource() is needed: the user
            // chooses in the picker, which start() shows.
            capturer.setUseSystemPicker(true);
            capturer.start(this::onCaptureResult);

            setStatus("Choose a screen or window in the picker.");
        });
    }

    private void stopCapture(String message) {
        captureThread.execute(() -> {
            disposeCapturer();
            setStatus(message);
        });

        chooseButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void captureFrame() {
        if (capturer == null) {
            return;
        }

        try {
            capturer.captureFrame();
        }
        catch (RuntimeException e) {
            ended = true;
            setStatus("Capture failed: " + e.getMessage());
        }

        if (ended) {
            // Disposed of here, not in the callback that reported the end.
            disposeCapturer();

            SwingUtilities.invokeLater(() -> {
                chooseButton.setEnabled(true);
                stopButton.setEnabled(false);
            });
        }
    }

    /**
     * Called on the capture thread from within {@link DesktopCapturer#captureFrame()}.
     */
    private void onCaptureResult(DesktopCapturer.Result result, VideoFrame frame) {
        try {
            switch (result) {
                case SUCCESS:
                    if (frame != null) {
                        view.accept(frame);
                        frames++;

                        setStatus(String.format("Capturing: %d frames, %dx%d", frames,
                                frame.buffer.getWidth(), frame.buffer.getHeight()));
                    }
                    break;

                case ERROR_TEMPORARY:
                    // No frame yet, while the picker waits for the user.
                    if (lastResult != DesktopCapturer.Result.ERROR_TEMPORARY && frames == 0) {
                        setStatus("Waiting for a choice in the picker…");
                    }
                    break;

                default:
                    ended = true;
                    setStatus(frames == 0
                            ? "The picker was cancelled."
                            : "Sharing stopped after " + frames + " frames.");
                    break;
            }

            lastResult = result;
        }
        finally {
            if (frame != null) {
                frame.release();
            }
        }
    }

    private void disposeCapturer() {
        if (capturer != null) {
            capturer.dispose();
            capturer = null;
        }
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> status.setText(text));
    }
}
