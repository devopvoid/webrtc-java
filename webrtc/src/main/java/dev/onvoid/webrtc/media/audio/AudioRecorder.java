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

/**
 * High-level recorder that captures audio from a configured {@link AudioDevice}
 * and forwards frames to an {@link AudioSink}. Manages the lifecycle of the
 * underlying {@link AudioDeviceModule} and offers thread-safe start/stop control.
 *
 * @author Alex Andres
 */
public class AudioRecorder {

	/** Indicates whether the recording is currently active (thread-safe). */
	private final AtomicBoolean capturing;

	/** Underlying audio device module handling the native capture. */
	private AudioDeviceModule module;

	/** The audio input device to record from. */
	private AudioDevice device;

	/** The sink receiving captured audio data. */
	private AudioSink sink;


	/**
	 * Creates a new AudioRecorder with an inactive capture state.
	 */
	public AudioRecorder() {
		capturing = new AtomicBoolean();
	}

	/**
	 * Set the audio input device to be used for recording.
	 *
	 * @param device the recording device; may be null until set.
	 */
	public void setAudioDevice(AudioDevice device) {
		this.device = device;
	}

	/**
	 * Set the sink that will receive captured audio frames.
	 *
	 * @param sink the audio sink implementation.
	 */
	public void setAudioSink(AudioSink sink) {
		this.sink = sink;
	}

	/**
	 * Start recording if not already active. Initializes and starts the underlying
	 * AudioDeviceModule.
	 */
	public void start() {
		if (capturing.compareAndSet(false, true)) {
			module = new AudioDeviceModule();
			module.setRecordingDevice(device);
			module.setAudioSink(sink);
			module.initRecording();
			module.startRecording();
		}
	}

	/**
	 * Stop recording if active and release resources.
	 */
	public void stop() {
		if (capturing.compareAndSet(true, false)) {
			module.stopRecording();
			module.dispose();
		}
	}
}
