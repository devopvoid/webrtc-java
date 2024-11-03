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

package dev.onvoid.webrtc.media;

import static org.junit.jupiter.api.Assertions.*;

import dev.onvoid.webrtc.media.audio.AudioDevice;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDevice;

import java.util.List;

import org.junit.jupiter.api.Test;

class MediaDevicesTests {

	@Test
	void getAudioDevices() {
		/*
		List<AudioDevice> captureDevices = MediaDevices.getAudioCaptureDevices();
		List<AudioDevice> renderDevices = MediaDevices.getAudioRenderDevices();

		assertNotNull(captureDevices);
		assertNotNull(renderDevices);
  		*/
	}

	@Test
	void getVideoDevices() {
		/*
		List<VideoDevice> captureDevices = MediaDevices.getVideoCaptureDevices();

		assertNotNull(captureDevices);

		for (VideoDevice device : captureDevices) {
			List<VideoCaptureCapability> capabilities = MediaDevices.getVideoCaptureCapabilities(device);

			assertNotNull(capabilities);
		}
		*/
	}

	@Test
	void deviceChangeListener() {
		/*
		DeviceChangeListener listener = new DeviceChangeListener() {

			@Override
			public void deviceConnected(Device device) {

			}

			@Override
			public void deviceDisconnected(Device device) {

			}
		};

		MediaDevices.addDeviceChangeListener(listener);
		MediaDevices.removeDeviceChangeListener(listener);
		*/
	}
}
