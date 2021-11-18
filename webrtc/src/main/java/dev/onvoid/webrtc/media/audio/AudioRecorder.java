/*
 * Copyright 2021 Alex Andres
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

import java.util.concurrent.atomic.AtomicBoolean;

public class AudioRecorder {

	private final AtomicBoolean capturing;

	private AudioDeviceModule module;

	private AudioDevice device;

	private AudioSink sink;


	public AudioRecorder() {
		capturing = new AtomicBoolean();
	}

	public void setAudioDevice(AudioDevice device) {
		this.device = device;
	}

	public void setAudioSink(AudioSink sink) {
		this.sink = sink;
	}

	public void start() {
		if (capturing.compareAndSet(false, true)) {
			module = new AudioDeviceModule();
			module.setRecordingDevice(device);
			module.setAudioSink(sink);
			module.initRecording();
			module.startRecording();
		}
	}

	public void stop() {
		if (capturing.compareAndSet(true, false)) {
			module.stopRecording();
			module.dispose();
		}
	}

}
