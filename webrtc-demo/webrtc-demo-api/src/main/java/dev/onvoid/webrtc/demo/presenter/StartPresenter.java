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

import dev.onvoid.webrtc.demo.context.ApplicationContext;
import dev.onvoid.webrtc.demo.event.LoggedInEvent;
import dev.onvoid.webrtc.demo.service.PeerConnectionService;
import dev.onvoid.webrtc.demo.view.StartView;

import javax.inject.Inject;

public class StartPresenter extends Presenter<StartView> {

	private final ApplicationContext context;

	private final PeerConnectionService peerConnectionService;


	@Inject
	StartPresenter(StartView view, ApplicationContext context, PeerConnectionService peerConnectionService) {
		super(view);

		this.context = context;
		this.peerConnectionService = peerConnectionService;
	}

	@Override
	public void initialize() {
		view.setUsername(context.getMyContact().getName());
		view.setOnConnect(this::onConnect);
	}

	private void onConnect() {
		context.getMyContact().setName(view.getUsername());

		peerConnectionService.login(context.getMyContact())
				.thenRunAsync(this::onConnectSuccess)
				.exceptionally(this::onConnectError);
	}

	private void onConnectSuccess() {
		publishEvent(new LoggedInEvent(context.getMyContact()));
	}

	private Void onConnectError(Throwable error) {
		view.setError(error.getCause().getMessage());
		return null;
	}

}
