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

package dev.onvoid.webrtc.demo.javafx;

import dev.onvoid.webrtc.demo.inject.ApplicationModule;
import dev.onvoid.webrtc.demo.inject.GuiceInjector;
import dev.onvoid.webrtc.demo.inject.Injector;
import dev.onvoid.webrtc.demo.javafx.inject.FxViewModule;
import dev.onvoid.webrtc.demo.presenter.MainPresenter;
import dev.onvoid.webrtc.demo.view.MainView;
import dev.onvoid.webrtc.logging.Logging;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Application;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class DemoApplication extends Application {

	@Override
	public void start(Stage primaryStage) throws Exception {
		Injector injector = new GuiceInjector(new ApplicationModule(), new FxViewModule());

		MainPresenter mainPresenter = injector.getInstance(MainPresenter.class);
		MainView mainView = mainPresenter.getView();

		if (!Parent.class.isAssignableFrom(mainView.getClass())) {
			throw new Exception("Main view must be a subclass of a JavaFX parent node");
		}

		mainPresenter.initialize();

		primaryStage.setTitle("WebRTC Demo");
		primaryStage.setScene(new Scene((Parent) mainView));
		primaryStage.getIcons().addAll(loadIcons("icon-32.png", "icon-64.png", "icon-128.png", "icon-256.png"));
		primaryStage.show();
	}

	public static void main(String[] args) {
		Logging.logThreads(true);

		launch(args);
	}

	private List<Image> loadIcons(String... files) {
		List<Image> images = new ArrayList<>(files.length);

		for (String file : files) {
			images.add(new Image(getClass().getResourceAsStream("/resources/icons/" + file)));
		}

		return images;
	}
}
