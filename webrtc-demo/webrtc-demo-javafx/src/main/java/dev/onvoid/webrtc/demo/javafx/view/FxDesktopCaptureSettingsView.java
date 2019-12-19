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

import dev.onvoid.webrtc.demo.beans.IntegerProperty;
import dev.onvoid.webrtc.demo.javafx.beans.IntegerPropertyAdapter;
import dev.onvoid.webrtc.demo.javafx.util.FxUtils;
import dev.onvoid.webrtc.demo.view.DesktopCaptureSettingsView;

import javafx.fxml.FXML;
import javafx.scene.control.Spinner;
import javafx.scene.layout.VBox;

@FxmlView(name = "desktop-capture-settings", presenter = dev.onvoid.webrtc.demo.presenter.DesktopCaptureSettingsPresenter.class)
public class FxDesktopCaptureSettingsView extends VBox implements DesktopCaptureSettingsView {

	@FXML
	private Spinner<Integer> frameRateField;


	@Override
	public void setFrameRate(IntegerProperty frameRate) {
		FxUtils.invoke(() -> {
			frameRateField.getValueFactory().valueProperty().bindBidirectional(
					new IntegerPropertyAdapter(frameRate).asObject());
		});
	}
}
