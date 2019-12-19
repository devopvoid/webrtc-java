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

package dev.onvoid.webrtc.demo.presenter;

import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;

import dev.onvoid.webrtc.demo.event.ApplicationEvent;
import dev.onvoid.webrtc.demo.view.Action;
import dev.onvoid.webrtc.demo.view.View;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.HashSet;
import java.util.Set;

/**
 *
 *
 * @author Alex Andres
 *
 * @param <T> The type of the view.
 */
public abstract class Presenter<T extends View> {

	private static Logger LOGGER = System.getLogger(Presenter.class.getName());

	protected final T view;

	private Presenter<?> parent;

	private Set<Presenter<?>> children;

	private Action closeAction;


	Presenter(T view) {
		requireNonNull(view);

		this.view = view;
		this.children = new HashSet<>();
	}

	public T getView() {
		return view;
	}

	abstract public void initialize();

	public void close() {
		if (nonNull(closeAction)) {
			closeAction.execute();
		}
	}

	public void destroy() {
		for (Presenter<?> child : children) {
			child.destroy();
		}

		children.clear();
	}

	public void setOnClose(Action action) {
		this.closeAction = action;
	}

	protected void addChild(Presenter<?> child) {
		children.add(child);
	}

	protected void removeChild(Presenter<?> child) {
		children.remove(child);
	}

	protected void setParent(Presenter<?> parent) {
		this.parent = parent;
	}

	protected void publishEvent(ApplicationEvent event) {
		if (nonNull(parent)) {
			parent.handleEvent(event);
		}
	}

	protected void handleEvent(ApplicationEvent event) {
		for (Presenter<?> child : children) {
			child.handleEvent(event);
		}
	}

	protected void handleException(Throwable throwable, String errorMessage, String title) {
		handleException(throwable, errorMessage, title, null);
	}

	final protected void handleException(Throwable throwable, String throwMessage, String title, String message) {
		logException(throwable, throwMessage);

		showError(title, message);
	}

	final protected void logException(Throwable throwable, String throwMessage) {
		requireNonNull(throwable);
		requireNonNull(throwMessage);

		LOGGER.log(Level.ERROR, throwMessage, throwable);
	}

	final protected void showError(String title, String message) {
		requireNonNull(title);

//		if (context.getDictionary().contains(title)) {
//			title = context.getDictionary().get(title);
//		}
//		if (context.getDictionary().contains(message)) {
//			message = context.getDictionary().get(message);
//		}
//
//		context.getEventBus().post(new NotificationContext(NotificationType.ERROR, title, message));
	}
}
