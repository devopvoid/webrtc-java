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

/**
 * The listener that is called when changes to the {@link ObservableList} occur.
 *
 * @param <T>
 *
 * @author Alex Andres
 */
public interface ListChangeListener<T extends ObservableList<?>> {

	/**
	 * Called whenever one or more elements in the list have changed.
	 * 
	 * @param list The changed list.
	 * @param startIndex The starting index of the first changed element.
	 * @param itemCount The number of changed elements.
	 */
	default void listItemsChanged(T list, int startIndex, int itemCount) {
		listChanged(list);
	}

	/**
	 * Called whenever elements have been inserted into the list.
	 * 
	 * @param list The changed list.
	 * @param startIndex The position of the first inserted element.
	 * @param itemCount The number of inserted elements.
	 */
	default void listItemsInserted(T list, int startIndex, int itemCount) {
		listChanged(list);
	}

	/**
	 * Called whenever elements in the list have been moved.
	 * 
	 * @param list The changed list.
	 * @param startIndex The starting index from which the elements were moved.
	 * @param destIndex The new position of the elements.
	 * @param itemCount The number of moved elements.
	 */
	default void listItemsMoved(T list, int startIndex, int destIndex, int itemCount) {
		listChanged(list);
	}

	/**
	 * Called whenever elements in the list have been deleted.
	 * 
	 * @param list The changed list.
	 * @param startIndex The starting index of the first deleted element.
	 * @param itemCount The number of removed elements.
	 */
	default void listItemsRemoved(T list, int startIndex, int itemCount) {
		listChanged(list);
	}

	/**
	 * Called whenever items of the list have changed. This method is by default
	 * a no-op.
	 *
	 * @param list The changed list.
	 */
	default void listChanged(T list) {

	}

}
