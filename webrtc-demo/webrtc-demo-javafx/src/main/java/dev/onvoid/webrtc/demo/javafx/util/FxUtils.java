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

package dev.onvoid.webrtc.demo.javafx.util;

import static java.util.Objects.isNull;
import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.demo.view.Action;
import dev.onvoid.webrtc.demo.view.ConsumerAction;
import dev.onvoid.webrtc.demo.view.View;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ToggleButton;

public final class FxUtils {

	public static void bindAction(ButtonBase button, Action action) {
		requireNonNull(button);
		requireNonNull(action);

		button.setOnAction(event -> action.execute());
	}

	public static void bindAction(ToggleButton toggle, ConsumerAction<Boolean> action) {
		requireNonNull(toggle);
		requireNonNull(action);

		toggle.setOnAction(event -> action.execute(toggle.isSelected()));
	}

	public static void checkNodeView(View view) {
		if (!Node.class.isAssignableFrom(view.getClass())) {
			throw new IllegalArgumentException("View expected to be a JavaFX Node.");
		}
	}

	/**
	 * Run this Runnable in the JavaFX Application Thread. This method can be
	 * called whether or not the current thread is the JavaFX Application
	 * Thread.
	 *
	 * @param runnable The code to be executed in the JavaFX Application Thread.
	 */
	public static void invoke(Runnable runnable) {
		if (isNull(runnable)) {
			return;
		}

		try {
			if (Platform.isFxApplicationThread()) {
				runnable.run();
			}
			else {
				Platform.runLater(runnable);
			}
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
