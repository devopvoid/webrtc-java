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
import dev.onvoid.webrtc.demo.model.Room;
import dev.onvoid.webrtc.demo.service.PeerConnectionService;
import dev.onvoid.webrtc.demo.view.StartView;

import java.net.ConnectException;
import java.util.ResourceBundle;

import javax.inject.Inject;

public class StartPresenter extends Presenter<StartView> {

	private final ApplicationContext context;

	private final PeerConnectionService peerConnectionService;

	private ResourceBundle resources;

	private Room room;


	@Inject
	StartPresenter(StartView view, ApplicationContext context,
				   PeerConnectionService peerConnectionService,
				   ResourceBundle resources) {
		super(view);

		this.context = context;
		this.peerConnectionService = peerConnectionService;
		this.resources = resources;
	}

	@Override
	public void initialize() {
		view.setRoomName("");
		view.setOnConnect(this::onConnect);
	}

	private void onConnect() {
		room = new Room(view.getRoomName());

		peerConnectionService.login(context.getMyContact(), room)
				.thenRunAsync(this::onConnectSuccess)
				.exceptionally(this::onConnectError);
	}

	private void onConnectSuccess() {
		publishEvent(new LoggedInEvent(context.getMyContact(), room));
	}

	private Void onConnectError(Throwable error) {
		logException(error, "Joining room failed");

		String message;

		if (error.getCause() instanceof ConnectException) {
			message = "start.connection.error";
		}
		else {
			message = error.getCause().getMessage().toLowerCase();

			if (message.equals("full")) {
				message = "start.room.full";
			}
		}

		view.setError(getTranslation(resources, message));

		return null;
	}

}
