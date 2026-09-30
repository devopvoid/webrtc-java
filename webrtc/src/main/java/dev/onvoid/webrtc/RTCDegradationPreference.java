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

package dev.onvoid.webrtc;

/**
 * What a video sender gives up first when the network or the CPU cannot keep
 * up: frame rate, resolution, or a balance of both. Set on the {@link
 * RTCRtpSendParameters#degradationPreference send parameters}.
 *
 * @author Alex Andres
 */
public enum RTCDegradationPreference {

	/**
	 * Keep both frame rate and resolution, and drop frames instead where
	 * necessary. Quality adaptation is off.
	 */
	MAINTAIN_FRAMERATE_AND_RESOLUTION,

	/**
	 * Keep the frame rate, and lower the resolution. Suits motion, such as
	 * camera video of people.
	 */
	MAINTAIN_FRAMERATE,

	/**
	 * Keep the resolution, and lower the frame rate. Suits detail, such as
	 * shared screens with text.
	 */
	MAINTAIN_RESOLUTION,

	/**
	 * Lower both frame rate and resolution in turns.
	 */
	BALANCED

}
