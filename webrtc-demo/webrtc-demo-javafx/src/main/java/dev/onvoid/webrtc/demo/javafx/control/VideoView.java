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

import dev.onvoid.webrtc.demo.javafx.control.skin.VideoViewSkin;
import dev.onvoid.webrtc.media.video.VideoFrame;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.WritableValue;
import javafx.css.StyleableProperty;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;

public class VideoView extends Control {

	private static final String DEFAULT_STYLE_CLASS = "video-view";

	private final BooleanProperty resize = new SimpleBooleanProperty(true);


	public VideoView() {
		initialize();
	}

	public void setVideoFrame(VideoFrame frame) {
		VideoViewSkin skin = (VideoViewSkin) getSkin();

		if (nonNull(skin)) {
			skin.setVideoFrame(frame);
		}
	}

	public final BooleanProperty resizeProperty() {
		return resize;
	}

	public final boolean getResize() {
		return resizeProperty().get();
	}

	public final void setResize(boolean resize) {
		resizeProperty().set(resize);
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new VideoViewSkin(this);
	}

	@Override
	protected Boolean getInitialFocusTraversable() {
		return Boolean.FALSE;
	}

	private void initialize() {
		getStyleClass().setAll(DEFAULT_STYLE_CLASS);

		final StyleableProperty<Boolean> prop = (StyleableProperty<Boolean>)(WritableValue<Boolean>)focusTraversableProperty();
		prop.applyStyle(null, Boolean.FALSE);
	}
}
