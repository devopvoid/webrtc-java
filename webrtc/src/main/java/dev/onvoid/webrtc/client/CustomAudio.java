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
 * An audio track fed with audio the application pushes, made by {@link
 * LocalMedia#customAudio(String)}.
 * <p>
 * Audio may be pushed in pieces of any length, from any thread. The track
 * cuts it into 10 ms chunks and sends one every 10 ms from a thread of its
 * own, so pushing a whole second at once is as fine as pushing every 10 ms.
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
	 */
	void push(byte[] pcm, PcmFormat format);

	/**
	 * @return the audio queued and not sent yet, in milliseconds.
	 */
	long queuedMillis();

	/**
	 * Drops the audio queued and not sent yet.
	 */
	void clear();

}
