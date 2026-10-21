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

package dev.onvoid.webrtc.client;

/**
 * A call does not fit how the runtime was built, such as asking for the microphone of a runtime whose {@link AudioMode} sends custom audio.
 */
public class ConfigurationException extends WebRtcException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception with a message that says what went wrong.
	 *
	 * @param message the detail message.
	 */
	public ConfigurationException(String message) {
		super(message);
	}

	/**
	 * Creates the exception with a message and the failure that caused it.
	 *
	 * @param message the detail message.
	 * @param cause   the underlying failure.
	 */
	public ConfigurationException(String message, Throwable cause) {
		super(message, cause);
	}

}
