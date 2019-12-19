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
 * An Observable allows to observe arbitrary content types for changes. Each
 * time the observed value changes the registered listeners will be notified.
 *
 * @param <T> The type of the observed data.
 *
 * @author Alex Andres
 */
public interface Observable<T> {

	/**
	 * Adds the given ChangeListener to be notified whenever the value of this
	 * Observable has changed.
	 *
	 * @param listener The listener to register.
	 *
	 * @throws NullPointerException If the listener is null.
	 */
	void addListener(ChangeListener<? super T> listener);

	/**
	 * Removes the given ChangeListener from the listener list.
	 *
	 * @param listener The listener to remove.
	 */
	void removeListener(ChangeListener<? super T> listener);

}
