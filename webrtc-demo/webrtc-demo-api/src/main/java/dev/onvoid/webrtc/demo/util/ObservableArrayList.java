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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * An {@link ObservableList} implementation backed by the {@link ArrayList}.
 * 
 * @author Alex Andres
 *
 * @param <T> The type of elements in the list.
 */
public class ObservableArrayList<T> extends ArrayList<T> implements ObservableList<T> {

	private static final long serialVersionUID = -2986628478190499263L;
	
	private final transient List<ListChangeListener<ObservableList<T>>> listeners = new ArrayList<>();
	

	@Override
	public boolean add(T element) {
		super.add(element);
		
		notifyInserted(size() - 1, 1);
		
		return true;
	}

	@Override
	public void add(int index, T element) {
		super.add(index, element);
		
		notifyInserted(index, 1);
	}
	
	@Override
	public boolean addAll(Collection<? extends T> collection) {
		int oldSize = size();
		boolean added = super.addAll(collection);
		
		if (added) {
			notifyInserted(oldSize, size() - oldSize);
		}
		
		return added;
	}

	@Override
	public boolean addAll(int index, Collection<? extends T> collection) {
		boolean added = super.addAll(index, collection);
		
		if (added) {
			notifyInserted(index, collection.size());
		}
		
		return added;
	}

	@Override
	public void clear() {
		int oldSize = size();
		super.clear();
		
		if (oldSize != 0) {
			notifyRemoved(0, oldSize);
		}
	}

	@Override
	public T remove(int index) {
		T element = super.remove(index);
		
		notifyRemoved(index, 1);
		
		return element;
	}

	@Override
	public boolean remove(Object object) {
		int index = indexOf(object);
		
		if (index >= 0) {
			remove(index);
			return true;
		}
		
		return false;
	}

	@Override
	public boolean removeIf(Predicate<? super T> filter) {
		boolean removed = super.removeIf(filter);

		if (removed) {
			notifyRemoved(0, 1);
		}

		return removed;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		int oldSize = size();
		boolean changed = super.retainAll(c);

		if (changed) {
			notifyChanged(0, oldSize);
		}

		return changed;
	}

	@Override
	public T set(int index, T object) {
		T element = super.set(index, object);
		
		notifyChanged(index, 1);
		
		return element;
	}
	
	@Override
	public void addListener(ListChangeListener<ObservableList<T>> listener) {
		listeners.add(listener);
	}

	@Override
	public void removeListener(ListChangeListener<ObservableList<T>> listener) {
		listeners.remove(listener);
	}
	
	@Override
	protected void removeRange(int fromIndex, int toIndex) {
		super.removeRange(fromIndex, toIndex);
		
		notifyRemoved(fromIndex, toIndex - fromIndex);
	}

	private void notifyInserted(int start, int count) {
		for (ListChangeListener<ObservableList<T>> listener : listeners) {
			listener.listItemsInserted(this, start, count);
		}
	}

	private void notifyRemoved(int start, int count) {
		for (ListChangeListener<ObservableList<T>> listener : listeners) {
			listener.listItemsRemoved(this, start, count);
		}
	}

	private void notifyChanged(int start, int count) {
		for (ListChangeListener<ObservableList<T>> listener : listeners) {
			listener.listItemsChanged(this, start, count);
		}
	}

}
