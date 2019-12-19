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

package dev.onvoid.webrtc.media.audio;

import dev.onvoid.webrtc.internal.DisposableNativeObject;

import java.util.List;

public class AudioDeviceModule extends DisposableNativeObject {

	public AudioDeviceModule() {
		initialize();
	}

	public native void initPlayout();

	public native void initRecording();

	public native List<AudioDevice> getPlayoutDevices();

	public native List<AudioDevice> getRecordingDevices();

	public native void setPlayoutDevice(AudioDevice device);

	public native void setRecordingDevice(AudioDevice device);

	public native boolean isSpeakerMuted();

	public native boolean isMicrophoneMuted();

	public native int getSpeakerVolume();

	public native int getMaxSpeakerVolume();

	public native int getMinSpeakerVolume();

	public native int getMicrophoneVolume();

	public native int getMaxMicrophoneVolume();

	public native int getMinMicrophoneVolume();

	public native void setSpeakerVolume(int volume);

	public native void setSpeakerMute(boolean mute);

	public native void setMicrophoneVolume(int volume);

	public native void setMicrophoneMute(boolean mute);

	@Override
	public native void dispose();

	private native void initialize();

}
