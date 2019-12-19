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

import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.demo.javafx.util.FxUtils;
import dev.onvoid.webrtc.demo.view.Action;
import dev.onvoid.webrtc.demo.view.MainView;
import dev.onvoid.webrtc.demo.view.View;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

@FxmlView(name = "main-window")
public class FxMainView extends BorderPane implements MainView {

	private Action closeAction;


	@Override
	public void close() {
		// Fire close request in order to shutdown appropriately.
		Window window = getScene().getWindow();
		window.fireEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
	}

	@Override
	public void hide() {
		FxUtils.invoke(() -> {
			Window window = getScene().getWindow();
			window.hide();
		});
	}

	@Override
	public void setFullscreen(boolean activate) {
		FxUtils.invoke(() -> {
			Stage stage = (Stage) getScene().getWindow();
			stage.setFullScreen(activate);
		});
	}

	@Override
	public void setView(View view) {
		FxUtils.checkNodeView(view);

		FxUtils.invoke(() -> {
			setCenter((Node) view);
		});
	}

	@Override
	public void setOnClose(Action action) {
		closeAction = action;
	}

	@FXML
	private void initialize() {
		sceneProperty().addListener(new ChangeListener<>() {

			@Override
			public void changed(ObservableValue<? extends Scene> observableValue, Scene oldScene, Scene newScene) {
				if (nonNull(newScene)) {
					sceneProperty().removeListener(this);

					onSceneSet(newScene);
				}
			}
		});
	}

	private void onSceneSet(Scene scene) {
		scene.windowProperty().addListener(new ChangeListener<>() {

			@Override
			public void changed(ObservableValue<? extends Window> observable, Window oldWindow, Window newWindow) {
				if (nonNull(newWindow)) {
					scene.windowProperty().removeListener(this);

					onStageSet((Stage) newWindow);
				}
			}
		});
	}

	private void onStageSet(Stage stage) {
		stage.setOnCloseRequest(event -> {
			// Consume event. Don't close the window yet.
			event.consume();

			executeAction(closeAction);
		});
	}
}
