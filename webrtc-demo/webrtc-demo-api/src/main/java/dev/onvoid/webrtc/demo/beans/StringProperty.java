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

package dev.onvoid.webrtc.demo.beans;

/**
 * String property implementation.
 *
 * @author Alex Andres
 */
public class StringProperty extends ObjectProperty<String> {

	/**
	 * Create a StringProperty with the initial value set to {@code null}.
	 */
	public StringProperty() {
		super(null);
	}

	/**
	 * Create a StringProperty with the specified initial value.
	 *
	 * @param defaultValue The initial value.
	 */
	public StringProperty(String defaultValue) {
		set(defaultValue);
	}

}
