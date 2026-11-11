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

import java.time.Duration;

/**
 * Pushed audio was not queued, since it would take the queue of its {@link
 * CustomAudio} beyond the limit that {@link CustomAudio#queueLimit(Duration,
 * AudioOverflow)} set with {@link AudioOverflow#REJECT}. None of it was kept,
 * so it can be pushed again once the queue shrank.
 */
public class AudioQueueFullException extends WebRtcException {

	private static final long serialVersionUID = 1L;

	/**
	 * Creates the exception with a message that says what went wrong.
	 *
	 * @param message the detail message.
	 */
	public AudioQueueFullException(String message) {
		super(message);
	}

}
