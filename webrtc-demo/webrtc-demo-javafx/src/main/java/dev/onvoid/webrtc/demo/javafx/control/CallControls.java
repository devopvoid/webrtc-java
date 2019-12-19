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

import dev.onvoid.webrtc.demo.javafx.control.skin.CallControlsSkin;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.WritableValue;
import javafx.css.StyleableProperty;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;

public class CallControls extends Control {

	private static final String DEFAULT_STYLE_CLASS = "call-controls";

	private final BooleanProperty desktopActive = new SimpleBooleanProperty();

	private final BooleanProperty microphoneActive = new SimpleBooleanProperty();

	private final BooleanProperty cameraActive = new SimpleBooleanProperty();

	private final BooleanProperty fullscreenActive = new SimpleBooleanProperty();

	private final BooleanProperty statsActive = new SimpleBooleanProperty();

	private final ObjectProperty<EventHandler<ActionEvent>> disconnectAction = new SimpleObjectProperty<>();


	public CallControls() {
		initialize();
	}

	public final BooleanProperty desktopActiveProperty() {
		return desktopActive;
	}

	public final boolean isDesktopActive() {
		return desktopActiveProperty().get();
	}

	public final void setDesktopActive(boolean active) {
		desktopActiveProperty().set(active);
	}

	public final BooleanProperty microphoneActiveProperty() {
		return microphoneActive;
	}

	public final boolean isMicrophoneActive() {
		return microphoneActiveProperty().get();
	}

	public final void setMicrophoneActive(boolean active) {
		microphoneActiveProperty().set(active);
	}

	public final BooleanProperty cameraActiveProperty() {
		return cameraActive;
	}

	public final boolean isCameraActive() {
		return cameraActiveProperty().get();
	}

	public final void setCameraActive(boolean active) {
		cameraActiveProperty().set(active);
	}

	public final BooleanProperty fullscreenActiveProperty() {
		return fullscreenActive;
	}

	public final boolean isFullscreenActive() {
		return fullscreenActiveProperty().get();
	}

	public final void setFullscreenActive(boolean active) {
		fullscreenActiveProperty().set(active);
	}

	public final BooleanProperty statsActiveProperty() {
		return statsActive;
	}

	public final boolean isStatsActive() {
		return statsActiveProperty().get();
	}

	public final void setStatsActive(boolean active) {
		statsActiveProperty().set(active);
	}

	public final ObjectProperty<EventHandler<ActionEvent>> disconnectActionProperty() {
		return disconnectAction;
	}

	public final void setDisconnectAction(EventHandler<ActionEvent> value) {
		disconnectActionProperty().set(value);
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new CallControlsSkin(this);
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
