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
import dev.onvoid.webrtc.demo.view.AudioSettingsView;
import dev.onvoid.webrtc.media.audio.AudioDevice;

import java.util.List;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

@FxmlView(name = "audio-settings", presenter = dev.onvoid.webrtc.demo.presenter.AudioSettingsPresenter.class)
public class FxAudioSettingsView extends VBox implements AudioSettingsView {

	@FXML
	private ComboBox<AudioDevice> recordingDeviceCombo;

	@FXML
	private ComboBox<AudioDevice> playoutDeviceCombo;

	@FXML
	private CheckBox receiveAudioCheckBox;

	@FXML
	private CheckBox sendAudioCheckBox;


	@Override
	public void setAudioPlayoutDevices(List<AudioDevice> devices) {
		FxUtils.invoke(() -> {
			playoutDeviceCombo.getItems().setAll(devices);
		});
	}

	@Override
	public void setAudioRecordingDevices(List<AudioDevice> devices) {
		FxUtils.invoke(() -> {
			recordingDeviceCombo.getItems().setAll(devices);
		});
	}

	@Override
	public void setAudioPlayoutDevice(ObjectProperty<AudioDevice> device) {
		FxUtils.invoke(() -> {
			playoutDeviceCombo.valueProperty().bindBidirectional(new ObjectPropertyAdapter<>(device));
		});
	}

	@Override
	public void setAudioRecordingDevice(ObjectProperty<AudioDevice> device) {
		FxUtils.invoke(() -> {
			recordingDeviceCombo.valueProperty().bindBidirectional(new ObjectPropertyAdapter<>(device));
		});
	}

	@Override
	public void setReceiveAudio(BooleanProperty receiveAudio) {
		FxUtils.invoke(() -> {
			receiveAudioCheckBox.selectedProperty().bindBidirectional(new BooleanPropertyAdapter(receiveAudio));
		});
	}

	@Override
	public void setSendAudio(BooleanProperty sendAudio) {
		FxUtils.invoke(() -> {
			sendAudioCheckBox.selectedProperty().bindBidirectional(new BooleanPropertyAdapter(sendAudio));
		});
	}
}
