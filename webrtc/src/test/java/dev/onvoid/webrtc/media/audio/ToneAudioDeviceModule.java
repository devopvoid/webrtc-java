/*
 * Copyright 2026 Alex Andres
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
 * An audio device module without audio hardware that records a sine tone, 48
 * kHz mono, while WebRTC records from it. It stands in for a microphone, so
 * that the tests can send device-captured audio. It lives in the tests so that
 * nothing else can reach it.
 *
 * @author Alex Andres
 */
public class ToneAudioDeviceModule extends AudioDeviceModuleBase {

	/**
	 * Creates a module that records a tone of the given frequency.
	 *
	 * @param frequencyHz The frequency of the recorded tone.
	 */
	public ToneAudioDeviceModule(int frequencyHz) {
		initialize(frequencyHz);
	}

	private native void initialize(int frequencyHz);

}
