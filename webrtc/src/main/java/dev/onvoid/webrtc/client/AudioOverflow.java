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
 * What a {@link CustomAudio} does with audio pushed beyond its {@link
 * CustomAudio#queueLimit(Duration, AudioOverflow) queue limit}.
 */
public enum AudioOverflow {

	/**
	 * Refuse the push with an {@link AudioQueueFullException}, keeping none of
	 * it, so that it can be pushed again once the queue shrank. Suits audio
	 * that must arrive whole, such as a file.
	 */
	REJECT,

	/**
	 * Take the push and drop the oldest queued audio to make room, so that
	 * what is sent stays at most the limit behind. Suits live audio, where
	 * late audio is worth less than lost audio.
	 */
	DROP_OLDEST

}
