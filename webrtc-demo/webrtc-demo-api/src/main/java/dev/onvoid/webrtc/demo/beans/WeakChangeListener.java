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

import java.lang.ref.WeakReference;

/**
 * The {@code WeakChangeListener} maintains a weak reference to the provided
 * listener and unregisters itself from the observed object once the listener is
 * garbage collected.
 *
 * @param <T> The type of the observed data.
 *
 * @author Alex Andres
 */
public class WeakChangeListener<T> implements ChangeListener<T> {

	private final WeakReference<ChangeListener<T>> ref;


	/**
	 * Creates a new {@code WeakChangeListener}.
	 *
	 * @param listener The listener that should be notified.
	 */
	public WeakChangeListener(ChangeListener<T> listener) {
		if (listener == null) {
			throw new NullPointerException("Listener must be specified");
		}

		ref = new WeakReference<>(listener);
	}

	@Override
	public void changed(Observable<? extends T> observable, T oldValue, T newValue) {
		ChangeListener<T> listener = ref.get();

		if (listener != null) {
			listener.changed(observable, oldValue, newValue);
		}
		else {
			observable.removeListener(this);
		}
	}
}
