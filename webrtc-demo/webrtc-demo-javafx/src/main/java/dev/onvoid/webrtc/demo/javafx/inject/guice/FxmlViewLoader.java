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

package dev.onvoid.webrtc.demo.javafx.inject.guice;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import com.google.inject.spi.InjectionListener;

import dev.onvoid.webrtc.demo.javafx.view.FxmlView;
import dev.onvoid.webrtc.demo.util.AggregateBundle;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URL;
import java.util.ResourceBundle;

import javax.inject.Provider;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.util.BuilderFactory;

public class FxmlViewLoader implements InjectionListener {

	private static Logger LOGGER = System.getLogger(FxmlViewLoader.class.getName());

	private static final String VIEW_RESOURCES = "/resources/views/";

	private final Provider<AggregateBundle> resourceProvider;

	private final Provider<BuilderFactory> builderProvider;

	private static FxmlViewLoader instance;


	private FxmlViewLoader(Provider<AggregateBundle> resourceProvider, Provider<BuilderFactory> builderProvider) {
		this.resourceProvider = resourceProvider;
		this.builderProvider = builderProvider;
	}

	public static FxmlViewLoader getInstance(Provider<AggregateBundle> resourceProvider, Provider<BuilderFactory> builderProvider) {
		if (FxmlViewLoader.instance == null) {
			FxmlViewLoader.instance = new FxmlViewLoader(resourceProvider, builderProvider);
		}
		return FxmlViewLoader.instance;
	}

	@Override
	public void afterInjection(Object view) {
		AggregateBundle resourceBundle = resourceProvider.get();
		BuilderFactory builderFactory = builderProvider.get();

		Class<?> superClass = view.getClass().getSuperclass();

		while (nonNull(superClass) && superClass.isAnnotationPresent(FxmlView.class)) {
			FxmlView superAnnotation = superClass.getAnnotation(FxmlView.class);

			load(view, superAnnotation, resourceBundle, builderFactory);

			superClass = superClass.getSuperclass();
		}

		FxmlView viewAnnotation = view.getClass().getAnnotation(FxmlView.class);

		load(view, viewAnnotation, resourceBundle, builderFactory);
	}

	private void load(Object view, FxmlView viewAnnotation, AggregateBundle resourceBundle, BuilderFactory builderFactory) {
		String viewName = viewAnnotation.name();
		String resourcePrefix = VIEW_RESOURCES + viewName + "/" + viewName;
		String fxmlFile = resourcePrefix + ".fxml";
		String dictPath = resourcePrefix.substring(1).replaceAll("[\\\\/]", ".");

		loadDictionary(resourceBundle, dictPath);

		if (isNull(getClass().getResource(fxmlFile))) {
			return;
		}

		Parent viewNode = loadView(view, fxmlFile, resourceBundle, builderFactory);

		if (nonNull(viewNode)) {
			URL cssURL = getClass().getResource(resourcePrefix + ".css");

			if (nonNull(cssURL)) {
				viewNode.getStylesheets().add(cssURL.toExternalForm());
			}
		}
	}

	private void loadDictionary(AggregateBundle resourceBundle, String dictPath) {
		try {
			resourceBundle.load(dictPath);
		}
		catch (IOException e) {
			LOGGER.log(Level.ERROR, "Load FXML view dictionary failed", e);
		}
	}

	private Parent loadView(Object view, String fxmlFile, ResourceBundle resourceBundle, BuilderFactory builderFactory) {
		try {
			URL fxmlURL = FxmlViewLoader.class.getResource(fxmlFile);

			FXMLLoader fxmlLoader = new FXMLLoader(fxmlURL, resourceBundle, builderFactory);
			fxmlLoader.setController(view);
			fxmlLoader.setRoot(view);

			return fxmlLoader.load();
		}
		catch (IOException e) {
			LOGGER.log(Level.ERROR, "Load FXML view failed", e);
		}

		return null;
	}

}
