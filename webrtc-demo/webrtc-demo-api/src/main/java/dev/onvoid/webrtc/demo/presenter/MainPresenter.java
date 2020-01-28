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

import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.demo.context.ViewContextFactory;
import dev.onvoid.webrtc.demo.event.ApplicationEvent;
import dev.onvoid.webrtc.demo.event.FullscreenEvent;
import dev.onvoid.webrtc.demo.event.LoggedInEvent;
import dev.onvoid.webrtc.demo.event.ShowViewEvent;
import dev.onvoid.webrtc.demo.model.Contact;
import dev.onvoid.webrtc.demo.service.PeerConnectionService;
import dev.onvoid.webrtc.demo.view.MainView;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

public class MainPresenter extends Presenter<MainView> {

	private static Logger LOGGER = System.getLogger(MainPresenter.class.getName());

	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	private final ViewContextFactory viewFactory;

	private final PeerConnectionService peerConnectionService;

	private final Stack<Class<Presenter<?>>> presenterStack;

	private Presenter<?> currentPresenter;


	@Inject
	MainPresenter(MainView view, ViewContextFactory viewFactory, PeerConnectionService peerConnectionService) {
		super(view);

		this.viewFactory = viewFactory;
		this.peerConnectionService = peerConnectionService;
		this.presenterStack = new Stack<>();
	}

	@Override
	public void handleEvent(ApplicationEvent event) {
		if (event instanceof LoggedInEvent) {
			handleEvent((LoggedInEvent) event);
		}
		else if (event instanceof FullscreenEvent) {
			handleEvent((FullscreenEvent) event);
		}
		else if (event instanceof ShowViewEvent) {
			handleEvent((ShowViewEvent) event);
		}

		super.handleEvent(event);
	}

	@Override
	public void initialize() {
		peerConnectionService.setOnConnectionState(this::onConnectionState);

		view.setOnClose(this::onClose);

		showStart();
	}

	@Override
	public void destroy() {
		peerConnectionService.setOnConnectionState(null);
		peerConnectionService.logout().thenRun(peerConnectionService::dispose);

		executor.shutdown();

		super.destroy();
	}

	private void showStart() {
		show(StartPresenter.class);
	}

	private void showContacts() {
		show(ContactsPresenter.class);
	}

	private void showCall() {
		show(CallPresenter.class);
	}

	private void handleEvent(LoggedInEvent event) {
		showCall();
	}

	private void handleEvent(FullscreenEvent event) {
		view.setFullscreen(event.getActivate());
	}

	private void handleEvent(ShowViewEvent event) {
		show(event.getPresenterClass());
	}

	private void onClose() {
		destroy();

		view.hide();
	}

	private void onConnectionState(Contact contact, RTCPeerConnectionState state) {
		switch (state) {
			case CLOSED:
			case DISCONNECTED:
			case FAILED:
				showStart();
				break;

			case CONNECTED:
				showCall();
				break;
		}
	}

	private void hideCurrentPresenter() {
		removeChild(currentPresenter);

		try {
			currentPresenter.destroy();
		}
		catch (Throwable t) {
			LOGGER.log(Level.ERROR, "Destroy presenter failed", t);
		}

		presenterStack.push((Class<Presenter<?>>) currentPresenter.getClass());
		currentPresenter = null;
	}

	private void show(Class<? extends Presenter<?>> cls) {
		executor.execute(() -> {
			if (nonNull(currentPresenter)) {
				if (currentPresenter.getClass().equals(cls)) {
					return;
				}

				hideCurrentPresenter();
			}

			try {
				Presenter<?> presenter = createPresenter(cls);

				addChild(presenter);

				currentPresenter = presenter;

				view.setView(presenter.getView());
			}
			catch (Throwable t) {
				LOGGER.log(Level.ERROR, "Show view-presenter failed", t);
			}
		});
	}

	private <T extends Presenter<?>> T createPresenter(Class<T> cls) {
		T presenter = viewFactory.getInstance(cls);
		presenter.setParent(this);
		presenter.setOnClose(() -> show(presenterStack.pop()));
		presenter.initialize();

		return presenter;
	}

}
