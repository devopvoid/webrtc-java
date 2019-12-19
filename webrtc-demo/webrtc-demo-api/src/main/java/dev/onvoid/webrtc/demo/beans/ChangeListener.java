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
 * A ChangeListener is notified whenever the value of an {@link Observable} has
 * changed.
 *
 * @param <T> The type of the observed data.
 *
 * @author Alex Andres
 */
@FunctionalInterface
public interface ChangeListener<T> {

	/**
	 * This method is called when the value of an {@code Observable} has changed.
	 *
	 * @param observable The Observable of which the value has changed.
	 * @param oldValue   The old value.
	 * @param newValue   The new value.
	 */
	void changed(Observable<? extends T> observable, T oldValue, T newValue);

}
