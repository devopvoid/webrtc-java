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
 * Used to receive events from the {@link RTCDataChannel}.
 *
 * @author Alex Andres
 */
public interface RTCDataChannelObserver {

	/**
	 * The RTCDataChannel's buffered amount has changed.
	 *
	 * @param previousAmount The previous buffer amount.
	 */
	void onBufferedAmountChange(long previousAmount);

	/**
	 * The RTCDataChannel's state has changed.
	 */
	void onStateChange();

	/**
	 * A data buffer was successfully received.
	 * <p>
	 * NOTE: {@link RTCDataChannelBuffer#data} will be freed once this function
	 * returns so observers who want to use the data asynchronously must make
	 * sure to copy it first.
	 *
	 * @param buffer The buffer containing the received message.
	 */
	void onMessage(RTCDataChannelBuffer buffer);

}
