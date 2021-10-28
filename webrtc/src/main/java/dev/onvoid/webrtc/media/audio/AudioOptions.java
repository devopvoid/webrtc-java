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
 * Audio options to control AudioTracks.
 *
 * @link https://www.w3.org/TR/mediacapture-streams/
 *
 * @author Alex Andres
 */
public class AudioOptions {

	/**
	 * When one or more audio streams is being played in the processes of
	 * various microphones, it is often desirable to attempt to remove the sound
	 * being played from the input signals recorded by the microphones. This is
	 * referred to as echo cancellation. There are cases where it is not needed
	 * and it is desirable to turn it off so that no audio artifacts are
	 * introduced. This allows applications to control this behavior.
	 */
	public boolean echoCancellation;

	/**
	 * Automatic gain control is often desirable on the input signal recorded by
	 * the microphone. There are cases where it is not needed and it is
	 * desirable to turn it off so that the audio is not altered. This allows
	 * applications to control this behavior.
	 */
	public boolean autoGainControl;

	/**
	 * Noise suppression is often desirable on the input signal recorded by the
	 * microphone. There are cases where it is not needed and it is desirable to
	 * turn it off so that the audio is not altered. This allows applications to
	 * control this behavior.
	 */
	public boolean noiseSuppression;

	/**
	 * Audio processing to remove background noise of lower frequencies.
	 */
	public boolean highpassFilter;

	/**
	 * Audio processing to detect typing.
	 */
	public boolean typingDetection;

	/**
	 * Audio processing to detect residual echoes.
	 */
	public boolean residualEchoDetector;

}
