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
 * A future of this API was waited on from an event thread. That thread is the one that would complete the future, so waiting there would never return. Chain the next step with {@code thenAccept} or {@code thenCompose} instead.
 */
public class BlockingCallException extends WebRtcException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception with a message that says what went wrong.
	 *
	 * @param message the detail message.
	 */
	public BlockingCallException(String message) {
		super(message);
	}

	/**
	 * Creates the exception with a message and the failure that caused it.
	 *
	 * @param message the detail message.
	 * @param cause   the underlying failure.
	 */
	public BlockingCallException(String message, Throwable cause) {
		super(message, cause);
	}

}
