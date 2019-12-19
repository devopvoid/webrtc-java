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
 * Object property implementation.
 *
 * @param <T> The type of the object value.
 *
 * @author Alex Andres
 */
public class ObjectProperty<T> extends ObservableBase<T> implements Property<T> {

	/** The object value. */
	private T value;


	/**
	 * Create an ObjectProperty with the initial value set to {@code null}.
	 */
	public ObjectProperty() {
		this(null);
	}

	/**
	 * Create an ObjectProperty with the specified initial value.
	 *
	 * @param value The initial value.
	 */
	public ObjectProperty(T value) {
		set(value);
	}

	public void notifyValueChanged() {
		fireChange(this, value, value);
	}

	@Override
	public T get() {
		return value;
	}

	@Override
	public void set(T newValue) {
		if (value != newValue) {
			T oldValue = value;
			value = newValue;

			fireChange(this, oldValue, newValue);
		}
	}

}
