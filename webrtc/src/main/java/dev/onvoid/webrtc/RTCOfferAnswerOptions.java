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
 * The RTCOfferAnswerOptions describe the options that can be used to control
 * the offer/answer creation process.
 *
 * @author Alex Andres
 */
public class RTCOfferAnswerOptions {

	/**
	 * Many codecs and systems are capable of detecting "silence" and changing
	 * their behavior in this case by doing things such as not transmitting any
	 * media. In many cases, such as when dealing with emergency calling or
	 * sounds other than spoken voice, it is desirable to be able to turn off
	 * this behavior. This option allows the application to provide information
	 * about whether it wishes this type of processing enabled or disabled.
	 */
	public boolean voiceActivityDetection;


	/**
	 * Creates an instance of RTCOfferAnswerOptions.
	 */
	public RTCOfferAnswerOptions() {
		voiceActivityDetection = true;
	}

}
