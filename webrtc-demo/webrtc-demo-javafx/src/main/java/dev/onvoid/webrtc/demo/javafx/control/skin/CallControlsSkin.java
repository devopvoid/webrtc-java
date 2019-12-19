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

package dev.onvoid.webrtc.demo.javafx.control.skin;

import dev.onvoid.webrtc.demo.javafx.control.CallControls;
import dev.onvoid.webrtc.demo.javafx.control.SvgIcon;

import javafx.geometry.HPos;
import javafx.scene.control.Button;
import javafx.scene.control.SkinBase;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;

public class CallControlsSkin extends SkinBase<CallControls> {

	/**
	 * Creates a new CallControlsSkin.
	 *
	 * @param control The control for which this Skin should attach to.
	 */
	public CallControlsSkin(CallControls control) {
		super(control);

		initLayout(control);
	}

	@Override
	public void dispose() {
		CallControls control = getSkinnable();

		unregisterChangeListeners(control.desktopActiveProperty());
		unregisterChangeListeners(control.microphoneActiveProperty());
		unregisterChangeListeners(control.cameraActiveProperty());
		unregisterChangeListeners(control.fullscreenActiveProperty());

		super.dispose();
	}

	private void initLayout(CallControls control) {
		control.getStylesheets().add(getClass().getResource("/resources/css/call-controls.css").toExternalForm());
		control.setMaxHeight(Region.USE_PREF_SIZE);

		GridPane pane = new GridPane();
		pane.setMaxHeight(Region.USE_PREF_SIZE);

		RowConstraints row = new RowConstraints();
		row.setVgrow(Priority.NEVER);

		ColumnConstraints column1 = new ColumnConstraints(50);
		ColumnConstraints column2 = new ColumnConstraints();
		ColumnConstraints column3 = new ColumnConstraints(50);

		column1.setHgrow(Priority.ALWAYS);
		column2.setHgrow(Priority.ALWAYS);
		column3.setHgrow(Priority.ALWAYS);

		pane.getRowConstraints().add(row);
		pane.getColumnConstraints().addAll(column1, column2, column3);

		SvgIcon desktopIcon = new SvgIcon();
		desktopIcon.getStyleClass().addAll("desktop-icon");
		desktopIcon.setMouseTransparent(true);

		SvgIcon microphoneIcon = new SvgIcon();
		microphoneIcon.getStyleClass().addAll("microphone-icon");
		microphoneIcon.setMouseTransparent(true);

		SvgIcon cameraIcon = new SvgIcon();
		cameraIcon.getStyleClass().add("camera-icon");
		cameraIcon.setMouseTransparent(true);

		SvgIcon disconnectIcon = new SvgIcon();
		disconnectIcon.getStyleClass().add("disconnect-icon");
		disconnectIcon.setMouseTransparent(true);

		SvgIcon fullscreenIcon = new SvgIcon();
		fullscreenIcon.getStyleClass().add("fullscreen-icon");
		fullscreenIcon.setMouseTransparent(true);

		SvgIcon statsIcon = new SvgIcon();
		statsIcon.getStyleClass().add("stats-icon");
		statsIcon.setMouseTransparent(true);

		ToggleButton desktopButton = new ToggleButton();
		desktopButton.getStyleClass().addAll("desktop");
		desktopButton.setPickOnBounds(false);
		desktopButton.setGraphic(desktopIcon);

		ToggleButton microphoneButton = new ToggleButton();
		microphoneButton.getStyleClass().addAll("round-button", "microphone");
		microphoneButton.setPickOnBounds(false);
		microphoneButton.setGraphic(microphoneIcon);

		ToggleButton cameraButton = new ToggleButton();
		cameraButton.getStyleClass().addAll("round-button", "camera");
		cameraButton.setPickOnBounds(false);
		cameraButton.setGraphic(cameraIcon);

		Button disconnectButton = new Button();
		disconnectButton.getStyleClass().addAll("round-button", "disconnect");
		disconnectButton.setPickOnBounds(false);
		disconnectButton.setGraphic(disconnectIcon);
		disconnectButton.onActionProperty().bind(control.disconnectActionProperty());

		ToggleButton fullscreenButton = new ToggleButton();
		fullscreenButton.getStyleClass().add("fullscreen");
		fullscreenButton.setPickOnBounds(false);
		fullscreenButton.setGraphic(fullscreenIcon);

		ToggleButton statsButton = new ToggleButton();
		statsButton.getStyleClass().addAll("stats");
		statsButton.setPickOnBounds(false);
		statsButton.setGraphic(statsIcon);

		HBox leftBox = new HBox(desktopButton);
		HBox centerBox = new HBox(microphoneButton, disconnectButton, cameraButton);
		HBox rightBox = new HBox(statsButton, fullscreenButton);

		leftBox.getStyleClass().add("left");
		centerBox.getStyleClass().add("center");
		rightBox.getStyleClass().add("right");

		leftBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		centerBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		rightBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

		GridPane.setHalignment(leftBox, HPos.LEFT);
		GridPane.setHalignment(centerBox, HPos.CENTER);
		GridPane.setHalignment(rightBox, HPos.RIGHT);

		pane.add(leftBox, 0, 0);
		pane.add(centerBox, 1, 0);
		pane.add(rightBox, 2, 0);

		control.desktopActiveProperty().bindBidirectional(desktopButton.selectedProperty());
		control.microphoneActiveProperty().bind(microphoneButton.selectedProperty().not());
		control.cameraActiveProperty().bind(cameraButton.selectedProperty().not());
		control.fullscreenActiveProperty().bindBidirectional(fullscreenButton.selectedProperty());
		control.statsActiveProperty().bindBidirectional(statsButton.selectedProperty());

		registerChangeListener(control.microphoneActiveProperty(), o -> {
			microphoneButton.setSelected(!control.isMicrophoneActive());
		});
		registerChangeListener(control.cameraActiveProperty(), o -> {
			cameraButton.setSelected(!control.isCameraActive());
		});

		getChildren().add(pane);
	}
}
