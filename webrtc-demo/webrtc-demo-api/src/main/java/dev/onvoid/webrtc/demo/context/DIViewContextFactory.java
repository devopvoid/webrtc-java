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

package dev.onvoid.webrtc.demo.context;

import dev.onvoid.webrtc.demo.inject.Injector;

import javax.inject.Inject;

public class DIViewContextFactory implements ViewContextFactory {

	private final Injector injector;


	@Inject
	public DIViewContextFactory(Injector injector) {
		this.injector = injector;
	}

	@Override
	public <T> T getInstance(Class<T> cls) {
		return injector.getInstance(cls);
	}
}
