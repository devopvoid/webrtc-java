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

import dev.onvoid.webrtc.demo.context.ViewContextFactory;
import dev.onvoid.webrtc.demo.event.FullscreenEvent;
import dev.onvoid.webrtc.demo.event.ShowViewEvent;
import dev.onvoid.webrtc.demo.service.PeerConnectionService;
import dev.onvoid.webrtc.demo.view.CallView;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import javax.inject.Inject;

public class CallPresenter extends Presenter<CallView> {

	private static Logger LOGGER = System.getLogger(CallPresenter.class.getName());

	private final ViewContextFactory viewFactory;

	private final PeerConnectionService peerConnectionService;


	@Inject
	CallPresenter(CallView view, ViewContextFactory viewFactory,
			PeerConnectionService peerConnectionService) {
		super(view);

		this.viewFactory = viewFactory;
		this.peerConnectionService = peerConnectionService;
	}

	@Override
	public void initialize() {
		view.showLocalVideo(peerConnectionService.hasLocalVideoStream());
		view.setOnDesktopActive(this::onOnDesktopActive);
		view.setOnFullscreenActive(this::onFullscreenActive);
		view.setOnStatsActive(peerConnectionService::enableStats);
		view.setOnDisconnect(peerConnectionService::hangup);
		view.setOnMicrophoneActive(peerConnectionService::setMicrophoneActive);
		view.setOnCameraActive(peerConnectionService::setCameraActive);

		peerConnectionService.setOnLocalVideoStream(view::showLocalVideo);
		peerConnectionService.setOnRemoteVideoStream(view::showRemoteVideo);
		peerConnectionService.setOnLocalFrame(view::setLocalFrame);
		peerConnectionService.setOnRemoteFrame(view::setRemoteFrame);
		peerConnectionService.setOnStatsReport(view::setStatsReport);
	}

	@Override
	public void destroy() {
		peerConnectionService.setOnLocalVideoStream(null);
		peerConnectionService.setOnRemoteVideoStream(null);
		peerConnectionService.setOnLocalFrame(null);
		peerConnectionService.setOnRemoteFrame(null);
		peerConnectionService.setOnStatsReport(null);

		peerConnectionService.hangup();

		super.destroy();
	}

	private void onOnDesktopActive(boolean active) {
		if (active) {
			publishEvent(new ShowViewEvent(DesktopSourcesPresenter.class));
//			try {
//				var presenter = createPresenter(DesktopSourcesPresenter.class);
//
//				addChild(presenter);
//
//				view.showDesktopSourcesView(presenter.getView());
//			}
//			catch (Throwable t) {
//				LOGGER.log(Level.ERROR, "Show view-presenter failed", t);
//			}
		}
		else {

		}
	}

	private void onFullscreenActive(boolean active) {
		publishEvent(new FullscreenEvent(active));
	}

	private <T extends Presenter<?>> T createPresenter(Class<T> cls) {
		T presenter = viewFactory.getInstance(cls);
		presenter.setParent(this);
		presenter.initialize();

		return presenter;
	}

}
