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

package dev.onvoid.webrtc.demo.javafx.view.builder;

import dev.onvoid.webrtc.demo.inject.Injector;
import dev.onvoid.webrtc.demo.presenter.Presenter;
import dev.onvoid.webrtc.demo.view.View;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import javafx.util.Builder;

public class ViewPresenterBuilder implements Builder<View> {

	private static Logger LOGGER = System.getLogger(ViewPresenterBuilder.class.getName());

	private final Injector injector;

	private final Class<? extends Presenter> presenterClass;


	ViewPresenterBuilder(Injector injector, Class<? extends Presenter> presenterClass) {
		this.injector = injector;
		this.presenterClass = presenterClass;
	}

	@Override
	public View build() {
		if (presenterClass != Presenter.class) {
			Presenter<?> presenter = injector.getInstance(presenterClass);

			try {
				presenter.initialize();
			}
			catch (Exception e) {
				LOGGER.log(Level.ERROR, "Initialize presenter failed", e);
			}

			return presenter.getView();
		}

		return null;
	}

}
