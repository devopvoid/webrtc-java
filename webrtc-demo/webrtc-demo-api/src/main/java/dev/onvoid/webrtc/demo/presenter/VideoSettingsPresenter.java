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

import dev.onvoid.webrtc.demo.config.Configuration;
import dev.onvoid.webrtc.demo.config.VideoConfiguration;
import dev.onvoid.webrtc.demo.view.VideoSettingsView;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDevice;

import java.util.Comparator;
import java.util.List;

import javax.inject.Inject;

public class VideoSettingsPresenter extends Presenter<VideoSettingsView> {

	private final VideoConfiguration config;


	@Inject
	VideoSettingsPresenter(VideoSettingsView view, Configuration config) {
		super(view);

		this.config = config.getVideoConfiguration();
	}

	@Override
	public void initialize() {
		var devices = MediaDevices.getVideoCaptureDevices();
		var device = config.getCaptureDevice();

		int devIndex = devices.indexOf(device);
		device = (devIndex < 0) ? devices.get(0) : devices.get(devIndex);

		var capabilities = MediaDevices.getVideoCaptureCapabilities(device);
		capabilities.sort(new VideoCaptureCapabilityComparator());
		var capability = getCaptureCapability(capabilities);

		config.setCaptureDevice(device);
		config.setCaptureCapability(capability);

		config.captureDeviceProperty().addListener((observable, oldDevice, newDevice) -> {
			onVideoCaptureDevice(newDevice);
		});

		view.setVideoDevices(devices);
		view.setVideoDevice(config.captureDeviceProperty());
		view.setVideoCaptureCapabilities(capabilities);
		view.setVideoCaptureCapability(config.captureCapabilityProperty());
		view.setReceiveVideo(config.receiveVideoProperty());
		view.setSendVideo(config.sendVideoProperty());
	}

	private void onVideoCaptureDevice(VideoDevice device) {
		var capabilities = MediaDevices.getVideoCaptureCapabilities(device);
		capabilities.sort(new VideoCaptureCapabilityComparator());
		var capability = getCaptureCapability(capabilities);

		view.setVideoCaptureCapabilities(capabilities);

		config.setCaptureCapability(capability);
		config.captureCapabilityProperty().notifyValueChanged();
	}

	private VideoCaptureCapability getCaptureCapability(List<VideoCaptureCapability> capabilities) {
		var capability = config.getCaptureCapability();

		int capIndex = capabilities.indexOf(capability);
		if (capIndex < 0) {
			// Select capability with HD quality.
			return capabilities.stream()
					.filter(cap -> cap.width == 1280 && cap.height == 720)
					.findAny()
					.orElse(capabilities.get(0));
		}

		return capabilities.get(capIndex);
	}



	static class VideoCaptureCapabilityComparator implements Comparator<VideoCaptureCapability> {

		@Override
		public int compare(VideoCaptureCapability a, VideoCaptureCapability b) {
			if (a.width == b.width) {
				if (a.height == b.height) {
					return a.frameRate - b.frameRate;
				}
				else {
					return a.height - b.height;
				}
			}
			return a.width - b.width;
		}
	}
}
