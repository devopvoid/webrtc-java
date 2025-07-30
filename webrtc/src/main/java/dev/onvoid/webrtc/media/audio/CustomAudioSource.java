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

/**
 * Custom implementation of an audio source for WebRTC that allows pushing audio data
 * from external sources directly to the WebRTC audio pipeline.
 *
 * @author Alex Andres
 */
public class CustomAudioSource extends AudioTrackSource {

	/**
	 * Constructs a new CustomAudioSource instance.
	 */
	public CustomAudioSource() {
		super();

		initialize();
	}

	/**
	 * Pushes audio data to be processed by this audio source.
	 *
	 * @param audioData       The raw audio data bytes to process.
	 * @param bits_per_sample The number of bits per sample (e.g., 8, 16, 32).
	 * @param sampleRate      The sample rate of the audio in Hz (e.g., 44100, 48000).
	 * @param channels        The number of audio channels (1 for mono, 2 for stereo).
	 * @param frameCount      The number of frames in the provided audio data.
	 */
	public native void pushAudio(byte[] audioData, int bits_per_sample,
								 int sampleRate, int channels, int frameCount);

	/**
	 * Initializes the native resources required by this audio source.
	 */
	private native void initialize();

}
