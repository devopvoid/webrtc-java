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
 * Receives the requests of an {@link RTCEncodedAudioFrameInjector}'s sender:
 * what the encoder would otherwise have answered.
 * <p>
 * The calls arrive in order on a native callback thread shared with other
 * WebRTC observers. An observer may call
 * other WebRTC methods, but should not block, since that holds up the other
 * observers.
 *
 * @author Alex Andres
 */
public interface RTCEncodedAudioFrameInjectorObserver {

	/**
	 * The bitrate the sender allows for the injected frames has changed.
	 *
	 * @param targetBitrate The target bitrate, in bits per second.
	 */
	void onTargetBitrateChange(int targetBitrate);

}
