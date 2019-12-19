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

import dev.onvoid.webrtc.demo.beans.BooleanProperty;
import dev.onvoid.webrtc.demo.beans.ObjectProperty;
import dev.onvoid.webrtc.demo.javafx.beans.BooleanPropertyAdapter;
import dev.onvoid.webrtc.demo.javafx.beans.ObjectPropertyAdapter;
import dev.onvoid.webrtc.demo.javafx.util.FxUtils;
import dev.onvoid.webrtc.demo.view.VideoSettingsView;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDevice;

import java.util.List;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

@FxmlView(name = "video-settings", presenter = dev.onvoid.webrtc.demo.presenter.VideoSettingsPresenter.class)
public class FxVideoSettingsView extends VBox implements VideoSettingsView {

	@FXML
	private ComboBox<VideoDevice> captureDeviceCombo;

	@FXML
	private ComboBox<VideoCaptureCapability> captureCapabilitiesCombo;

	@FXML
	private CheckBox receiveVideoCheckBox;

	@FXML
	private CheckBox sendVideoCheckBox;


	@Override
	public void setVideoDevices(List<VideoDevice> devices) {
		FxUtils.invoke(() -> {
			captureDeviceCombo.getItems().setAll(devices);
		});
	}

	@Override
	public void setVideoDevice(ObjectProperty<VideoDevice> device) {
		FxUtils.invoke(() -> {
			captureDeviceCombo.valueProperty().bindBidirectional(new ObjectPropertyAdapter<>(device));
		});
	}

	@Override
	public void setVideoCaptureCapabilities(List<VideoCaptureCapability> capabilities) {
		FxUtils.invoke(() -> {
			captureCapabilitiesCombo.getItems().setAll(capabilities);
		});
	}

	@Override
	public void setVideoCaptureCapability(ObjectProperty<VideoCaptureCapability> capability) {
		FxUtils.invoke(() -> {
			captureCapabilitiesCombo.valueProperty().bindBidirectional(new ObjectPropertyAdapter<>(capability));
		});
	}

	@Override
	public void setReceiveVideo(BooleanProperty receiveVideo) {
		FxUtils.invoke(() -> {
			receiveVideoCheckBox.selectedProperty().bindBidirectional(new BooleanPropertyAdapter(receiveVideo));
		});
	}

	@Override
	public void setSendVideo(BooleanProperty sendVideo) {
		FxUtils.invoke(() -> {
			sendVideoCheckBox.selectedProperty().bindBidirectional(new BooleanPropertyAdapter(sendVideo));
		});
	}
}
