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

import dev.onvoid.webrtc.RTCStatsReport;
import dev.onvoid.webrtc.demo.javafx.control.skin.StatsSkin;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.WritableValue;
import javafx.css.StyleableProperty;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;

public class Stats extends Control {

	private static final String DEFAULT_STYLE_CLASS = "stats";

	private final ObjectProperty<RTCStatsReport> statsReport = new SimpleObjectProperty<>();


	public Stats() {
		initialize();
	}

	public final ObjectProperty<RTCStatsReport> statsReportProperty() {
		return statsReport;
	}

	public final RTCStatsReport getStatsReport() {
		return statsReportProperty().get();
	}

	public final void setStatsReport(RTCStatsReport report) {
		statsReportProperty().set(report);
	}

	@Override
	public String getUserAgentStylesheet() {
		return getClass().getResource("/resources/css/stats.css").toExternalForm();
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new StatsSkin(this);
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
