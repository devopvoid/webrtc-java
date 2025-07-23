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

package dev.onvoid.webrtc;

/**
 * Observer interface for DTMF (Dual-Tone Multi-Frequency) tone events.
 * <p>
 * This interface allows applications to receive notifications when DTMF tones
 * are sent by an {@link RTCDtmfSender}. It corresponds to the
 * "tonechange" event in the W3C WebRTC specification.
 *
 * @author Alex Andres
 *
 * @see RTCDtmfSender
 */
public interface RTCDtmfSenderObserver {

	/**
	 * Called when a DTMF tone sent by an {@link RTCDtmfSender}.
	 *
	 * @param tone       The tone being played (one of "0123456789ABCD#*") or an
	 *                   empty string if the previous tone has finished playing.
	 * @param toneBuffer The remaining tones in the queue, including the current tone.
	 *                   Empty string if no tones remain.
	 */
	void onToneChange(String tone, String toneBuffer);

}
