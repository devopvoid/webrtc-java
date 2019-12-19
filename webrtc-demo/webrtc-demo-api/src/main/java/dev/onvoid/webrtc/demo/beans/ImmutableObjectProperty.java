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

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Immutable object property implementation.
 *
 * @author Alex Andres
 */
public class ImmutableObjectProperty<T> extends ObjectProperty<T> {

	/** The immutable property. */
	private ImmutableProperty immutableProperty;


	/**
	 * Create a ImmutableObjectProperty with the initial value set to {@code
	 * null}.
	 */
	public ImmutableObjectProperty() {

	}

	/**
	 * Create a ImmutableObjectProperty with the specified initial value.
	 *
	 * @param defaultValue The initial value.
	 */
	public ImmutableObjectProperty(T defaultValue) {
		set(defaultValue);
	}

	/**
	 * Get the immutable ObjectProperty.
	 *
	 * @return the immutable ObjectProperty.
	 */
	public ObjectProperty<T> getImmutableProperty() {
		if (isNull(immutableProperty)) {
			immutableProperty = new ImmutableProperty();
		}
		return immutableProperty;
	}

	@Override
	protected void fireChange(Observable<T> observable, T oldValue, T newValue) {
		super.fireChange(observable, oldValue, newValue);

		if (nonNull(immutableProperty)) {
			immutableProperty.fireChange(immutableProperty, oldValue, newValue);
		}
	}



	private class ImmutableProperty extends ObjectProperty<T> {

		@Override
		public T get() {
			return ImmutableObjectProperty.this.get();
		}

		@Override
		public void set(T value) {
			// No-op. Immutable.
		}
	}
}
