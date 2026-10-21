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

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import dev.onvoid.webrtc.client.AudioMode;
import dev.onvoid.webrtc.client.LocalCamera;
import dev.onvoid.webrtc.client.MediaException;
import dev.onvoid.webrtc.client.Peer;
import dev.onvoid.webrtc.client.PeerOptions;
import dev.onvoid.webrtc.client.Signaling;
import dev.onvoid.webrtc.client.SignalingPair;
import dev.onvoid.webrtc.client.VideoStats;
import dev.onvoid.webrtc.client.WebRtc;
import dev.onvoid.webrtc.client.javafx.VideoView;
import dev.onvoid.webrtc.media.video.VideoDevice;

/**
 * Example of the client API in a JavaFX application: a camera is sent from one peer to another
 * in the same process, and both its preview and the received video are shown.
 * <p>
 * This example shows how to:
 * <ul>
 *   <li>Run every handler of a {@link WebRtc} runtime on the JavaFX Application Thread</li>
 *   <li>Show a local camera preview and a remote track in the JavaFX {@link VideoView}</li>
 *   <li>Mirror the preview through the view's {@code mirrored} property</li>
 *   <li>Switch the camera while it is being sent, without renegotiating</li>
 *   <li>Show the connection state and the encoded resolution and frame rate</li>
 * </ul>
 * <p>
 * Both peers run in this process, connected through a loopback signaling. In a real application
 * each peer is in another process, and its {@link Signaling} carries the messages over a
 * WebSocket or the like.
 * <p>
 * Run it with a camera connected:
 * <pre>
 * mvn exec:java -D"exec.mainClass=dev.onvoid.webrtc.examples.JavaFxCameraExample"
 * </pre>
 *
 * @author Alex Andres
 */
public class JavaFxCameraExample {

    private final VideoView preview = new VideoView();
    private final VideoView remoteView = new VideoView();
    private final ComboBox<VideoDevice> cameraBox = new ComboBox<>();
    private final CheckBox mirrorBox = new CheckBox("Mirror preview");
    private final Label stateLabel = new Label("Starting");
    private final Label statsLabel = new Label();

    private WebRtc rtc;


    public static void main(String[] args) {
        // Started without an Application subclass, so that it also runs with JavaFX on the
        // class path, as exec:java puts it.
        Platform.startup(() -> new JavaFxCameraExample().show(new Stage()));
    }

    private void show(Stage stage) {
        // Only video is sent, so the runtime needs no audio devices. Every handler and every
        // future of a peer runs on the JavaFX Application Thread, so they may change the scene.
        rtc = WebRtc.builder()
                .audio(AudioMode.HEADLESS)
                .eventExecutor(Platform::runLater)
                .build();

        stage.setTitle("JavaFX Camera");
        stage.setScene(new Scene(layout(), 1000, 480));
        stage.setOnCloseRequest(event -> exit());
        stage.show();

        // The runtime is closed before JavaFX exits.
        Platform.setImplicitExit(false);

        List<VideoDevice> cameras = rtc.media().devices().cameras();

        if (cameras.isEmpty()) {
            stateLabel.setText("No camera found");
            return;
        }

        LocalCamera camera;

        try {
            camera = rtc.media().camera();
        }
        catch (MediaException e) {
            stateLabel.setText("The camera could not be opened: " + e.getMessage());
            return;
        }

        // The preview gets the camera's frames before they are encoded.
        camera.onFrame(preview);

        call(camera);

        cameraBox.getItems().setAll(cameras);
        cameraBox.getSelectionModel().select(camera.device());
        cameraBox.setDisable(cameras.size() < 2);
        cameraBox.setOnAction(event -> switchTo(camera, cameraBox.getValue()));
    }

    /**
     * Sends the camera from one peer to the other, and shows what arrives.
     */
    private void call(LocalCamera camera) {
        SignalingPair signaling = Signaling.loopback();

        Peer caller = rtc.createPeer(PeerOptions.builder()
                .name("caller")
                .signaling(signaling.first())
                .build());
        Peer callee = rtc.createPeer(PeerOptions.builder()
                .name("callee")
                .polite(true)
                .signaling(signaling.second())
                .build());

        // Both handlers run on the JavaFX Application Thread.
        caller.onStateChange(state -> stateLabel.setText("Caller is " + state));
        caller.onStats(Duration.ofSeconds(1), stats -> {
            if (!stats.outboundVideo().isEmpty()) {
                VideoStats video = stats.outboundVideo().get(0);

                statsLabel.setText(String.format("Sending %s %dx%d at %.0f fps, %d kbit/s", video.codec(),
                        video.width(), video.height(), video.framesPerSecond(),
                        Math.max(0, stats.outgoingBitrate()) / 1000));
            }
        });

        callee.onTrack(track -> {
            if (track.isVideo()) {
                // Frames are converted on the track's frame thread; the view only paints on the
                // JavaFX Application Thread.
                track.asVideo().onFrame(remoteView);
                track.onEnded(remoteView::clear);
            }
        });

        caller.addTrack(camera);
    }

    private void switchTo(LocalCamera camera, VideoDevice device) {
        if (device == null || device.equals(camera.device())) {
            return;
        }

        cameraBox.setDisable(true);
        stateLabel.setText("Switching to " + device.getName());

        // The peers keep sending, and the preview keeps showing, the new camera. The future
        // completes on a background thread.
        camera.switchCamera(device).whenComplete((done, error) -> Platform.runLater(() -> {
            cameraBox.setDisable(false);

            if (error != null) {
                stateLabel.setText("Switching failed: " + error.getMessage());
                cameraBox.getSelectionModel().select(camera.device());
            }
            else {
                stateLabel.setText("Sending " + camera.device().getName());
            }
        }));
    }

    private BorderPane layout() {
        preview.setMirrored(true);
        mirrorBox.selectedProperty().bindBidirectional(preview.mirroredProperty());

        cameraBox.setConverter(new StringConverter<VideoDevice>() {

            @Override
            public String toString(VideoDevice device) {
                // Two sensors of one camera can share a name.
                int index = cameraBox.getItems().indexOf(device);

                return device == null ? "" : (index + 1) + ": " + device.getName();
            }

            @Override
            public VideoDevice fromString(String text) {
                return null;
            }
        });

        HBox views = new HBox(8, titled("Camera", preview), titled("Received", remoteView));
        views.setPadding(new Insets(8));

        HBox controls = new HBox(12, new Label("Camera:"), cameraBox, mirrorBox, stateLabel, statsLabel);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(0, 8, 8, 8));

        BorderPane root = new BorderPane(views);
        root.setBottom(controls);

        return root;
    }

    private static VBox titled(String title, VideoView view) {
        VBox box = new VBox(4, new Label(title), view);
        VBox.setVgrow(view, Priority.ALWAYS);
        HBox.setHgrow(box, Priority.ALWAYS);

        return box;
    }

    private void exit() {
        // Closing waits for the peers and the camera, so it happens off the JavaFX thread.
        rtc.closeAsync().whenComplete((done, error) -> Platform.exit());
    }
}
