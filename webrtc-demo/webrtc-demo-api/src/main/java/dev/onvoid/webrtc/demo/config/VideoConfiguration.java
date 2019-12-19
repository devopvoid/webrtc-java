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

package dev.onvoid.webrtc.demo.config;

import dev.onvoid.webrtc.demo.beans.BooleanProperty;
import dev.onvoid.webrtc.demo.beans.ObjectProperty;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import dev.onvoid.webrtc.media.video.VideoDevice;

public class VideoConfiguration {

	private final ObjectProperty<VideoDevice> captureDevice;

	private final ObjectProperty<VideoCaptureCapability> captureCapability;

	private final BooleanProperty receiveVideo;

	private final BooleanProperty sendVideo;


	public VideoConfiguration() {
		captureDevice = new ObjectProperty<>();
		captureCapability = new ObjectProperty<>();
		receiveVideo = new BooleanProperty();
		sendVideo = new BooleanProperty();
	}

	public ObjectProperty<VideoDevice> captureDeviceProperty() {
		return captureDevice;
	}

	public VideoDevice getCaptureDevice() {
		return captureDevice.get();
	}

	public void setCaptureDevice(VideoDevice device) {
		captureDevice.set(device);
	}

	public ObjectProperty<VideoCaptureCapability> captureCapabilityProperty() {
		return captureCapability;
	}

	public VideoCaptureCapability getCaptureCapability() {
		return captureCapability.get();
	}

	public void setCaptureCapability(VideoCaptureCapability capability) {
		captureCapability.set(capability);
	}

	public BooleanProperty receiveVideoProperty() {
		return receiveVideo;
	}

	public boolean getReceiveVideo() {
		return receiveVideo.get();
	}

	public void setReceiveVideo(boolean receive) {
		receiveVideo.set(receive);
	}

	public BooleanProperty sendVideoProperty() {
		return sendVideo;
	}

	public boolean getSendVideo() {
		return sendVideo.get();
	}

	public void setSendVideo(boolean send) {
		sendVideo.set(send);
	}
}
