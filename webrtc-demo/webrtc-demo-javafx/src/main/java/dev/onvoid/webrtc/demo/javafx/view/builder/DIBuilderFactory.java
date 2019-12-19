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
import dev.onvoid.webrtc.demo.javafx.view.FxmlView;
import dev.onvoid.webrtc.demo.presenter.Presenter;

import java.lang.reflect.Constructor;

import javax.inject.Inject;

import javafx.fxml.JavaFXBuilderFactory;
import javafx.util.Builder;
import javafx.util.BuilderFactory;

public class DIBuilderFactory implements BuilderFactory {

	private static final String JAVAFX_PACKAGE = "javafx";

	private final BuilderFactory baseFactory;

	private final Injector injector;


	@Inject
	public DIBuilderFactory(Injector injector) {
		this.baseFactory = new JavaFXBuilderFactory();
		this.injector = injector;
	}

	@Override
	public Builder<?> getBuilder(Class<?> type) {
		if (type.getPackageName().startsWith(JAVAFX_PACKAGE)) {
			// No injection for standard classes.
			return baseFactory.getBuilder(type);
		}

		if (type.isAnnotationPresent(FxmlView.class)) {
			FxmlView viewAnnotation = type.getAnnotation(FxmlView.class);
			Class<? extends Presenter> presenterClass = viewAnnotation.presenter();

			return new ViewPresenterBuilder(injector, presenterClass);
		}

		Constructor[] constructors = type.getDeclaredConstructors();

		for (Constructor constructor : constructors) {
			if (constructor.isAnnotationPresent(Inject.class)) {
				return (Builder<Object>) () -> injector.getInstance(type);
			}
		}

		return baseFactory.getBuilder(type);
	}

}
