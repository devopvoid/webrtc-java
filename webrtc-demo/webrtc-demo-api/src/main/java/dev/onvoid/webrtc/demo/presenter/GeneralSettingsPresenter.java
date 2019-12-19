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

package dev.onvoid.webrtc.demo.presenter;

import dev.onvoid.webrtc.demo.config.Configuration;
import dev.onvoid.webrtc.demo.view.GeneralSettingsView;

import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

public class GeneralSettingsPresenter extends Presenter<GeneralSettingsView> {

	private final Configuration config;


	@Inject
	GeneralSettingsPresenter(GeneralSettingsView view, Configuration config) {
		super(view);

		this.config = config;
	}

	@Override
	public void initialize() {
		view.setLocales(List.of(Locale.UK, Locale.GERMANY));
		view.setLocale(config.localeProperty());
	}
}
