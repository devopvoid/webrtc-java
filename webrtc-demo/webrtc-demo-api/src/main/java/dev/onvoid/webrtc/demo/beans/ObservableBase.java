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

import java.util.ArrayList;
import java.util.List;

/**
 * Observable base implementation that manages the change listeners and event
 * notifications.
 *
 * @param <T> The type of the observed data.
 *
 * @author Alex Andres
 */
public abstract class ObservableBase<T> implements Observable<T> {

	/** The registered change listeners. */
	private final List<ChangeListener<? super T>> listeners = new ArrayList<>();


	@Override
	public void addListener(ChangeListener<? super T> listener) {
		listeners.add(listener);
	}

	@Override
	public void removeListener(ChangeListener<? super T> listener) {
		listeners.remove(listener);
	}

	/**
	 * Notify the change listeners that the observed value has changed.
	 *
	 * @param observable The observable that caused the event.
	 * @param oldValue   The old value.
	 * @param newValue   The new value.
	 */
	protected void fireChange(Observable<T> observable, T oldValue, T newValue) {
		for (ChangeListener<? super T> listener : listeners) {
			listener.changed(observable, oldValue, newValue);
		}
	}

}
