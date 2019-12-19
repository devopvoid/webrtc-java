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

import dev.onvoid.webrtc.demo.javafx.util.FxUtils;
import dev.onvoid.webrtc.demo.view.Action;
import dev.onvoid.webrtc.demo.view.SettingsView;

import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.layout.VBox;

@FxmlView(name = "settings")
public class FxSettingsView extends VBox implements SettingsView {

	@FXML
	private TabPane tabPane;

	@FXML
	private Button closeButton;


	@Override
	public void setOnClose(Action action) {
		FxUtils.bindAction(closeButton, action);
	}

	@FXML
	private void initialize() {
		sceneProperty().addListener(new InvalidationListener() {

			@Override
			public void invalidated(Observable observable) {
				sceneProperty().removeListener(this);

				tabPane.requestFocus();
			}
		});
	}
}
