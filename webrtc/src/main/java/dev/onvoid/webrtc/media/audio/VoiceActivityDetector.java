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

import dev.onvoid.webrtc.internal.DisposableNativeObject;

/**
 * A voice activity detector that analyzes audio data chunks to determine the probability of voice presence in the
 * audio stream.
 * <p>
 * This class provides native methods to process audio and retrieve the probability of voice activity.
 *
 * @author Alex Andres
 */
public class VoiceActivityDetector extends DisposableNativeObject {

	/**
	 * Constructs a new {@code VoiceActivityDetector} instance.
	 */
	public VoiceActivityDetector() {
		initialize();
	}

	/**
	 * Processes audio data to detect voice activity.
	 *
	 * @param audio             The audio data to process.
	 * @param samplesPerChannel The number of samples in the audio chunk.
	 * @param sampleRate        The sample rate of the audio data in Hz.
	 */
	public native void process(byte[] audio, int samplesPerChannel, int sampleRate);

	/**
	 * Gets the probability of voice presence from the last processed audio data.
	 *
	 * @return A value between 0.0 and 1.0 representing the probability of voice presence.
	 */
	public native float getLastVoiceProbability();

	/**
	 * Releases native resources held by this object.
	 */
	@Override
	public native void dispose();

	/**
	 * Initializes the native resources for the voice activity detector. This method is called during construction and
	 * sets up the underlying native implementation.
	 */
	private native void initialize();

}
