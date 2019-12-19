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

import dev.onvoid.webrtc.demo.beans.ObjectProperty;
import dev.onvoid.webrtc.demo.javafx.beans.ObjectPropertyAdapter;
import dev.onvoid.webrtc.demo.javafx.util.FxUtils;
import dev.onvoid.webrtc.demo.view.GeneralSettingsView;

import java.util.List;
import java.util.Locale;

import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.VBox;

@FxmlView(name = "general-settings", presenter = dev.onvoid.webrtc.demo.presenter.GeneralSettingsPresenter.class)
public class FxGeneralSettingsView extends VBox implements GeneralSettingsView {

	@FXML
	private ComboBox<Locale> localeCombo;


	@Override
	public void setLocales(List<Locale> locales) {
		FxUtils.invoke(() -> {
			localeCombo.getItems().setAll(locales);
		});
	}

	@Override
	public void setLocale(ObjectProperty<Locale> locale) {
		FxUtils.invoke(() -> {
			localeCombo.valueProperty().bindBidirectional(new ObjectPropertyAdapter<>(locale));
		});
	}
}
