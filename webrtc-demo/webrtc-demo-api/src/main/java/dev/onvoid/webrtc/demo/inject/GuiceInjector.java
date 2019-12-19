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

import com.google.inject.Guice;
import com.google.inject.Module;

import java.util.ArrayList;
import java.util.List;

public class GuiceInjector implements Injector {

	private final com.google.inject.Injector injector;


	public GuiceInjector(Module... modules) {
		List<Module> list = new ArrayList<>(List.of(modules));
		list.add(binder -> binder.bind(Injector.class).toInstance(this));

		injector = Guice.createInjector(list);
	}

	@Override
	public <T> T getInstance(Class<T> cls) {
		return injector.getInstance(cls);
	}
}
