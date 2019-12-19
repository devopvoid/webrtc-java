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
 * The RTCAnswerOptions describe options specific to the session description of
 * type answer.
 *
 * @author Alex Andres
 */
public class RTCOfferOptions extends RTCOfferAnswerOptions {

	/**
	 * When set to {@code true}, the generated description will have ICE
	 * credentials that are different from the current credentials. Applying the
	 * generated description will restart ICE.
	 * <p>
	 * When the value is set to {@code false}, and the current local description
	 * has valid ICE credentials, the generated description will have the same
	 * ICE credentials as the current value from the current local description.
	 */
	public boolean iceRestart;


	/**
	 * Creates an instance of RTCOfferOptions.
	 */
	public RTCOfferOptions() {
		super();

		iceRestart = false;
	}

}
