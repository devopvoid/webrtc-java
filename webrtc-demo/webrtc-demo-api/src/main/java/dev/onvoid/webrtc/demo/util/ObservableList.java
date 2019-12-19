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

package dev.onvoid.webrtc.demo.util;

import java.util.List;

/**
 * A {@link List} that notifies when changes occur.
 * 
 * @author Alex Andres
 *
 * @param <T>
 */
public interface ObservableList<T> extends List<T> {

	/**
	 * Adds a listener to be notified when changes to the list occur.
	 * 
	 * @param listener The listener to be notified on list changes.
	 */
	void addListener(ListChangeListener<ObservableList<T>> listener);

	/**
	 * Removes a previously added listener.
	 * 
	 * @param listener The listener to remove.
	 */
	void removeListener(ListChangeListener<ObservableList<T>> listener);
	
}
