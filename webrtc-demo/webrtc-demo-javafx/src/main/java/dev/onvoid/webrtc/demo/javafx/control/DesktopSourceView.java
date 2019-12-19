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

import static java.util.Objects.nonNull;

import dev.onvoid.webrtc.demo.javafx.control.skin.DesktopSourceViewSkin;
import dev.onvoid.webrtc.media.video.desktop.DesktopFrame;

import javafx.scene.control.Button;
import javafx.scene.control.Skin;

public class DesktopSourceView extends Button {

	private static final String DEFAULT_STYLE_CLASS = "desktop-source-view";


	public DesktopSourceView() {
		initialize();
	}

	public void setDesktopFrame(DesktopFrame frame) {
		DesktopSourceViewSkin skin = (DesktopSourceViewSkin) getSkin();

		if (nonNull(skin)) {
			skin.setDesktopFrame(frame);
		}
	}

	@Override
	public String getUserAgentStylesheet() {
		return getClass().getResource("/resources/css/desktop-source-view.css").toExternalForm();
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new DesktopSourceViewSkin(this);
	}

	private void initialize() {
		getStyleClass().add(DEFAULT_STYLE_CLASS);
	}
}
