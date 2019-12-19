/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.demo.javafx.view;

import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.demo.javafx.control.CallControls;
import dev.onvoid.webrtc.demo.javafx.control.Stats;
import dev.onvoid.webrtc.demo.javafx.control.VideoView;
import dev.onvoid.webrtc.demo.javafx.util.FxUtils;
import dev.onvoid.webrtc.demo.model.Contact;
import dev.onvoid.webrtc.demo.view.Action;
import dev.onvoid.webrtc.demo.view.CallView;
import dev.onvoid.webrtc.demo.view.ConsumerAction;
import dev.onvoid.webrtc.demo.view.DesktopSourcesView;
import dev.onvoid.webrtc.media.video.VideoFrame;

import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

@FxmlView(name = "call")
public class FxCallView extends BorderPane implements CallView {

	@FXML
	private StackPane contentPane;

	@FXML
	private VideoView localView;

	@FXML
	private VideoView remoteView;

	@FXML
	private Stats stats;

	@FXML
	private CallControls callControls;


	@Override
	public void showDesktopSourcesView(DesktopSourcesView view) {
		FxUtils.checkNodeView(view);

		FxUtils.invoke(() -> {
			Pane nodeView = (Pane) view;
			nodeView.setPrefSize(300, 100);
			nodeView.setMaxSize(300, 100);

			StackPane.setAlignment(nodeView, Pos.BOTTOM_CENTER);

			contentPane.getChildren().add(nodeView);

//			Scene scene = getScene();
//			Pane viewPane = (Pane) view;
//
//			PopupControl popup = new PopupControl();
//
//			viewPane.prefWidthProperty().bind(scene.widthProperty().divide(2));
//			viewPane.maxWidthProperty().bind(scene.widthProperty().divide(2));
//
//			InvalidationListener xListener = o -> {
//				var point = scene.getRoot().localToScreen((scene.getWidth() - viewPane.getWidth()) / 2, 0);
//
//				popup.setX(point.getX());
//			};
//			InvalidationListener yListener = o -> {
//				var point = scene.getRoot().localToScreen(0, callControls.getBoundsInParent().getMinY() - viewPane.getHeight());
//
//				popup.setY(point.getY());
//			};
//
//			scene.getWindow().xProperty().addListener(xListener);
//			scene.getWindow().yProperty().addListener(yListener);
//			scene.widthProperty().addListener(xListener);
//			scene.heightProperty().addListener(yListener);
//
//			popup.getScene().getStylesheets().addAll(scene.getRoot().getStylesheets());
//			popup.setConsumeAutoHidingEvents(false);
////			popup.setAutoHide(true);
//			popup.setAutoFix(false);
//			popup.setHideOnEscape(true);
//			popup.getScene().setRoot(new StackPane((Node) view));
//			popup.setOnCloseRequest(e -> {
//
//			});
//			popup.show(getScene().getWindow());
		});
	}

	@Override
	public void setRemoteFrame(Contact contact, VideoFrame frame) {
		remoteView.setVideoFrame(frame);
	}

	@Override
	public void setLocalFrame(VideoFrame frame) {
		localView.setVideoFrame(frame);
	}

	@Override
	public void setStatsReport(RTCStatsReport report) {
		FxUtils.invoke(() -> {
			stats.setStatsReport(report);
		});
	}

	@Override
	public void showRemoteVideo(Contact contact, boolean show) {
		FxUtils.invoke(() -> {
			remoteView.setVisible(show);
		});
	}

	@Override
	public void showLocalVideo(boolean show) {
		FxUtils.invoke(() -> {
			localView.setVisible(show);
		});
	}

	@Override
	public void setOnFullscreenActive(ConsumerAction<Boolean> action) {
		callControls.fullscreenActiveProperty().addListener(o -> {
			action.execute(callControls.isFullscreenActive());
		});
	}

	@Override
	public void setOnDesktopActive(ConsumerAction<Boolean> action) {
		callControls.desktopActiveProperty().addListener(o -> {
			action.execute(callControls.isDesktopActive());
		});
	}

	@Override
	public void setOnMicrophoneActive(ConsumerAction<Boolean> action) {
		callControls.microphoneActiveProperty().addListener(o -> {
			action.execute(callControls.isMicrophoneActive());
		});
	}

	@Override
	public void setOnCameraActive(ConsumerAction<Boolean> action) {
		callControls.cameraActiveProperty().addListener(o -> {
			action.execute(callControls.isCameraActive());
		});
	}

	@Override
	public void setOnStatsActive(ConsumerAction<Boolean> action) {
		callControls.statsActiveProperty().addListener(o -> {
			boolean active = callControls.isStatsActive();

			stats.setManaged(active);
			stats.setVisible(active);

			action.execute(active);
		});
	}

	@Override
	public void setOnDisconnect(Action action) {
		callControls.setDisconnectAction(e -> action.execute());
	}

}
