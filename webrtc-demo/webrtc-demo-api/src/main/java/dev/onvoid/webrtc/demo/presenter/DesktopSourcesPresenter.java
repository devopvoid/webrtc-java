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

import static java.util.Objects.isNull;

import dev.onvoid.webrtc.demo.context.ViewContextFactory;
import dev.onvoid.webrtc.demo.service.PeerConnectionService;
import dev.onvoid.webrtc.demo.view.DesktopSourceView;
import dev.onvoid.webrtc.demo.view.DesktopSourcesView;
import dev.onvoid.webrtc.media.video.desktop.DesktopCapturer;
import dev.onvoid.webrtc.media.video.desktop.DesktopFrame;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.DesktopSourceType;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;
import dev.onvoid.webrtc.media.video.desktop.WindowCapturer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

public class DesktopSourcesPresenter extends Presenter<DesktopSourcesView> {

	private final ViewContextFactory viewFactory;

	private final PeerConnectionService peerConnectionService;

	private final Map<DesktopSource, DesktopSourceView> screenSourceMap;

	private final Map<DesktopSource, DesktopSourceView> windowSourceMap;

	private final ScheduledExecutorService executorService;

	private ScheduledFuture<?> scheduledFuture;

	private ScreenCapturer screenCapturer;

	private WindowCapturer windowCapturer;

	private DesktopSource capturingScreenSource;

	private DesktopSource capturingWindowSource;


	@Inject
	DesktopSourcesPresenter(DesktopSourcesView view, ViewContextFactory viewFactory,
			PeerConnectionService peerConnectionService) {
		super(view);

		this.viewFactory = viewFactory;
		this.peerConnectionService = peerConnectionService;
		this.screenSourceMap = new ConcurrentHashMap<>();
		this.windowSourceMap = new ConcurrentHashMap<>();
		this.executorService = Executors.newSingleThreadScheduledExecutor();
	}

	@Override
	public void initialize() {
		screenCapturer = new ScreenCapturer();
		windowCapturer = new WindowCapturer();

		screenCapturer.start(this::onScreenCapture);
		windowCapturer.start(this::onWindowCapture);

		view.setOnDesktopSourceView(this::onDesktopSourceView);

		scheduledFuture = executorService.scheduleAtFixedRate(this::update, 0, 1, TimeUnit.SECONDS);
	}

	@Override
	public void destroy() {
		scheduledFuture.cancel(true);
		executorService.shutdownNow();

		screenCapturer.dispose();
		windowCapturer.dispose();

		super.destroy();
	}

	private void onDesktopSourceView(DesktopSourceView view) {
		DesktopSource source = view.getDesktopSource();


	}

	private void onScreenCapture(DesktopCapturer.Result result, DesktopFrame frame) {
		setDesktopFrame(result, frame, capturingScreenSource, screenSourceMap);
	}

	private void onWindowCapture(DesktopCapturer.Result result, DesktopFrame frame) {
		setDesktopFrame(result, frame, capturingWindowSource, windowSourceMap);
	}

	private void setDesktopFrame(DesktopCapturer.Result result,
			DesktopFrame frame, DesktopSource source,
			Map<DesktopSource, DesktopSourceView> map) {
		if (result != DesktopCapturer.Result.SUCCESS) {
			return;
		}
		if (isNull(source)) {
			return;
		}

		var view = map.get(source);

		if (isNull(view)) {
			return;
		}

		view.setDesktopFrame(frame);
	}

	private void update() {
		var screenSources = screenCapturer.getDesktopSources();
		var windowSources = windowCapturer.getDesktopSources();

		cleanSources(screenSources, screenSourceMap);
		cleanSources(windowSources, windowSourceMap);

		addSources(screenSources, screenSourceMap, DesktopSourceType.SCREEN);
		addSources(windowSources, windowSourceMap, DesktopSourceType.WINDOW);

		for (var source : screenSources) {
			capturingScreenSource = source;

			screenCapturer.selectSource(source);
			screenCapturer.captureFrame();
		}

		for (var source : windowSources) {
			capturingWindowSource = source;

			windowCapturer.selectSource(source);
			windowCapturer.captureFrame();
		}
	}

	private void cleanSources(List<DesktopSource> list,
			Map<DesktopSource, DesktopSourceView> map) {
		for (var source : map.keySet()) {
			if (!list.contains(source)) {
				var sourceView = map.remove(source);

				view.removeDesktopSourceView(sourceView);
			}
		}
	}

	private void addSources(List<DesktopSource> list,
			Map<DesktopSource, DesktopSourceView> map, DesktopSourceType type) {
		int screenId = 1;

		for (var source : list) {
			if (type == DesktopSourceType.SCREEN) {
				source = new DesktopSource("Screen #" + screenId++, source.id);
			}

			var sourceView = map.get(source);

			if (isNull(sourceView)) {
				sourceView = viewFactory.getInstance(DesktopSourceView.class);
				sourceView.setDesktopSource(source);
				sourceView.setDesktopSourceType(type);

				map.put(source, sourceView);

				view.addDesktopSourceView(sourceView);
			}
			else {
				sourceView.setDesktopSource(source);
			}
		}
	}
}
