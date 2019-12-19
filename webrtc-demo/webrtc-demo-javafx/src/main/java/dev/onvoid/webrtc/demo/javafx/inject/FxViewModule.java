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

package dev.onvoid.webrtc.demo.javafx.inject;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;

import dev.onvoid.webrtc.demo.config.Configuration;
import dev.onvoid.webrtc.demo.javafx.inject.guice.FxmlViewLoader;
import dev.onvoid.webrtc.demo.javafx.inject.guice.FxmlViewMatcher;
import dev.onvoid.webrtc.demo.javafx.view.*;
import dev.onvoid.webrtc.demo.javafx.view.builder.DIBuilderFactory;
import dev.onvoid.webrtc.demo.util.AggregateBundle;
import dev.onvoid.webrtc.demo.view.*;

import java.util.ResourceBundle;

import javax.inject.Provider;
import javax.inject.Singleton;

import javafx.util.BuilderFactory;

public class FxViewModule extends AbstractModule {

	@Override
	protected void configure() {
		bind(AudioSettingsView.class).to(FxAudioSettingsView.class);
		bind(CallView.class).to(FxCallView.class);
		bind(ChatTextMessageView.class).to(FxChatTextMessageView.class);
		bind(ContactsView.class).to(FxContactsView.class);
		bind(DesktopCaptureSettingsView.class).to(FxDesktopCaptureSettingsView.class);
		bind(DesktopSourceView.class).to(FxDesktopSourceView.class);
		bind(DesktopSourcesView.class).to(FxDesktopSourcesView.class);
		bind(GeneralSettingsView.class).to(FxGeneralSettingsView.class);
		bind(MainView.class).to(FxMainView.class);
		bind(SettingsView.class).to(FxSettingsView.class);
		bind(StartView.class).to(FxStartView.class);
		bind(VideoSettingsView.class).to(FxVideoSettingsView.class);

		bind(BuilderFactory.class).to(DIBuilderFactory.class);

		Provider<AggregateBundle> resourceProvider = getProvider(AggregateBundle.class);
		Provider<BuilderFactory> builderProvider = getProvider(BuilderFactory.class);

		bindListener(new FxmlViewMatcher(), new TypeListener() {

			@Override
			@SuppressWarnings("unchecked")
			public <I> void hear(TypeLiteral<I> type, TypeEncounter<I> encounter) {
				encounter.register(FxmlViewLoader.getInstance(resourceProvider, builderProvider));
			}

		});
	}

	@Provides
	@Singleton
	ResourceBundle provideResourceBundle(Configuration config) throws Exception {
		return new AggregateBundle(config.getLocale());
	}

	@Provides
	@Singleton
	AggregateBundle provideAggregateBundle(ResourceBundle resourceBundle) {
		return (AggregateBundle) resourceBundle;
	}

}
