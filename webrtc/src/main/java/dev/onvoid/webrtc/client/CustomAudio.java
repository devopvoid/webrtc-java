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
 * An audio track fed with audio the application pushes, made by {@link
 * LocalMedia#customAudio(String)}.
 * <p>
 * Audio may be pushed in pieces of any length, from any thread. The track
 * cuts it into 10 ms chunks and sends one every 10 ms from a thread of its
 * own, so pushing a whole second at once is as fine as pushing every 10 ms.
 * <p>
 * Should that thread stall, such as in a long garbage collection pause, it
 * makes up for at most the last 50 ms of the stall. The audio of the rest is
 * not sent faster but later, so what is queued then stays that much behind,
 * unless {@link #clear()} drops it.
 * <p>
 * The queue holds at most 10 seconds by default, beyond which a push fails
 * with an {@link AudioQueueFullException}, so that a producer faster than real
 * time cannot fill memory and let the delay grow without end. {@link
 * #queueLimit(Duration, AudioOverflow)} changes the limit and what happens
 * beyond it.
 */
public interface CustomAudio extends LocalAudio {

	/**
	 * Queues audio to send.
	 *
	 * @param pcm    16-bit little-endian PCM with interleaved channels, a whole
	 *               number of samples. The array is copied.
	 * @param format the sample rate and channel count. A format other than the
	 *               one before starts a new stream: audio still queued in the
	 *               old format is sent first.
	 *
	 * @throws AudioQueueFullException if the audio would take the queue beyond
	 *                                 its limit and the overflow is {@link
	 *                                 AudioOverflow#REJECT}. None of it is
	 *                                 queued then.
	 */
	void push(byte[] pcm, PcmFormat format);

	/**
	 * Limits how much audio the queue holds; 10 seconds with {@link
	 * AudioOverflow#REJECT} by default. The audio of a push that is shorter
	 * than 10 ms, or does not end on a 10 ms boundary, waits for the next push
	 * and does not count until it fills 10 ms.
	 * <p>
	 * A limit lower than what is queued drops the oldest audio at once with
	 * {@link AudioOverflow#DROP_OLDEST}; with {@link AudioOverflow#REJECT} the
	 * queued audio is sent, and pushes fail until it drained below the limit.
	 *
	 * @param max      the longest the queue may hold, at least 10 ms. It is
	 *                 rounded down to whole 10 ms.
	 * @param overflow what happens to audio pushed beyond the limit.
	 */
	void queueLimit(Duration max, AudioOverflow overflow);

	/**
	 * @return the audio queued and not sent yet, in milliseconds.
	 */
	long queuedMillis();

	/**
	 * Drops the audio queued and not sent yet.
	 */
	void clear();

}
