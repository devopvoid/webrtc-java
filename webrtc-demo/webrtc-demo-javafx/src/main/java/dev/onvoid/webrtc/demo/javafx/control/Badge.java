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

package dev.onvoid.webrtc.demo.javafx.control;

import javafx.geometry.Pos;
import javafx.scene.control.Label;

public class Badge extends Label {

	private static final String DEFAULT_STYLE_CLASS = "badge";


	public Badge() {
		super();

		initialize();
	}

	public Badge(String text) {
		super(text);

		initialize();
	}

	@Override
	public String getUserAgentStylesheet() {
		return getClass().getResource("/resources/css/badge.css").toExternalForm();
	}

	private void initialize() {
		getStyleClass().setAll(DEFAULT_STYLE_CLASS);

		setAlignment(Pos.CENTER);
		minWidthProperty().bind(heightProperty());
	}
}
