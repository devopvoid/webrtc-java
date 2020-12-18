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
import dev.onvoid.webrtc.media.audio.AudioDevice;

public class AudioConfiguration {

	private final ObjectProperty<AudioDevice> playoutDevice;

	private final ObjectProperty<AudioDevice> recordingDevice;

	private final BooleanProperty receiveAudio;

	private final BooleanProperty sendAudio;


	public AudioConfiguration() {
		playoutDevice = new ObjectProperty<>();
		recordingDevice = new ObjectProperty<>();
		receiveAudio = new BooleanProperty();
		sendAudio = new BooleanProperty();
	}

	public ObjectProperty<AudioDevice> playoutDeviceProperty() {
		return playoutDevice;
	}

	public AudioDevice getPlayoutDevice() {
		return playoutDevice.get();
	}

	public void setPlayoutDevice(AudioDevice device) {
		playoutDevice.set(device);
	}

	public ObjectProperty<AudioDevice> recordingDeviceProperty() {
		return recordingDevice;
	}

	public AudioDevice getRecordingDevice() {
		return recordingDevice.get();
	}

	public void setRecordingDevice(AudioDevice device) {
		recordingDevice.set(device);
	}

	public BooleanProperty receiveAudioProperty() {
		return receiveAudio;
	}

	public boolean getReceiveAudio() {
		return receiveAudio.get();
	}

	public void setReceiveAudio(boolean receive) {
		receiveAudio.set(receive);
	}

	public BooleanProperty sendAudioProperty() {
		return sendAudio;
	}

	public boolean getSendAudio() {
		return sendAudio.get();
	}

	public void setSendAudio(boolean send) {
		sendAudio.set(send);
	}
}
