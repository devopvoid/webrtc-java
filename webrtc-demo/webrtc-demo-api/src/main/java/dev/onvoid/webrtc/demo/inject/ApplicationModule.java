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

package dev.onvoid.webrtc.demo.inject;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.name.Names;

import dev.onvoid.webrtc.demo.config.Configuration;
import dev.onvoid.webrtc.demo.config.DefaultConfiguration;
import dev.onvoid.webrtc.demo.context.DIViewContextFactory;
import dev.onvoid.webrtc.demo.context.ViewContextFactory;
import dev.onvoid.webrtc.demo.net.HttpSignalingClient;
import dev.onvoid.webrtc.demo.net.SignalingClient;

import javax.inject.Singleton;

public class ApplicationModule extends AbstractModule {

	@Override
	protected void configure() {
		bindConstant().annotatedWith(Names.named("Signaling Server")).to("localhost");
		bindConstant().annotatedWith(Names.named("Signaling Server Port")).to(8888);

		bind(SignalingClient.class).to(HttpSignalingClient.class);
		bind(ViewContextFactory.class).to(DIViewContextFactory.class);
	}

	@Provides
	@Singleton
	Configuration provideConfiguration() {
		return new DefaultConfiguration();
	}

}
