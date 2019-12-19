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

package dev.onvoid.webrtc.demo.view;

import dev.onvoid.webrtc.demo.beans.BooleanProperty;
import dev.onvoid.webrtc.demo.beans.ObjectProperty;
import dev.onvoid.webrtc.media.audio.AudioDevice;

import java.util.List;

public interface AudioSettingsView extends View {

	void setAudioPlayoutDevices(List<AudioDevice> devices);

	void setAudioRecordingDevices(List<AudioDevice> devices);

	void setAudioPlayoutDevice(ObjectProperty<AudioDevice> device);

	void setAudioRecordingDevice(ObjectProperty<AudioDevice> device);

	void setReceiveAudio(BooleanProperty receiveAudio);

	void setSendAudio(BooleanProperty sendAudio);

}
