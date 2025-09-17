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
 * High-level wrapper to control audio playout. Provides idempotent start/stop semantics
 * around an underlying AudioDeviceModule.
 *
 * @author Alex Andres
 */
public class AudioPlayer {

	/**
	 * Indicates if the player is currently started. Used atomically to provide
	 * a thread-safe idempotent start/stop sequence.
	 */
	private final AtomicBoolean playing;

	/**
	 * Underlying audio device module managing the native playout resources.
	 * Created lazily on {@link #start()} and disposed on {@link #stop()}.
	 */
	private AudioDeviceModule module;

	/**
	 * The audio output device to use for playout. Should be set before calling {@link #start()}.
	 */
	private AudioDevice device;

	/**
	 * The audio source providing audio frames to be rendered by the device.
	 */
	private AudioSource source;


	/**
	 * Creates a new AudioPlayer instance in a non-playing state.
	 */
	public AudioPlayer() {
		playing = new AtomicBoolean();
	}

	/**
	 * Assigns the audio output device.
	 *
	 * @param device the target playout device (may be null to clear).
	 */
	public void setAudioDevice(AudioDevice device) {
		this.device = device;
	}

	/**
	 * Assigns the audio source supplying audio data.
	 *
	 * @param source the audio source (may be null to clear).
	 */
	public void setAudioSource(AudioSource source) {
		this.source = source;
	}

	/**
	 * Starts audio playout if not already running. This method is idempotent; subsequent
	 * calls while already playing have no effect.
	 * <p>
	 * Initializes and configures the underlying {@link AudioDeviceModule}.
	 */
	public void start() {
		if (playing.compareAndSet(false, true)) {
			module = new AudioDeviceModule();
			module.setPlayoutDevice(device);
			module.setAudioSource(source);
			module.initPlayout();
			module.startPlayout();
		}
	}

	/**
	 * Stops audio playout if currently running and releases underlying resources.
	 * This method is idempotent; calling when already stopped has no effect.
	 */
	public void stop() {
		if (playing.compareAndSet(true, false)) {
			module.stopPlayout();
			module.dispose();
		}
	}
}
