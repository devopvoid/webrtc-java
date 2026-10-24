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
import java.util.function.Consumer;

import dev.onvoid.webrtc.media.audio.AudioTrackSink;

/**
 * Hands the audio of a track to a handler on a thread of its own, in order.
 * When the chunks waiting for the handler last longer than the buffer, the
 * oldest are dropped.
 */
final class AudioDispatcher extends Dispatcher<AudioChunk> implements AudioTrackSink {

	/**
	 * @param buffer how much audio may wait for the handler.
	 */
	AudioDispatcher(Consumer<AudioChunk> handler, Duration buffer, SerialExecutor executor,
			Workload workload, Consumer<Throwable> errorHandler) {
		super(handler, micros(buffer), executor, workload, errorHandler);
	}

	@Override
	public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
		if (bitsPerSample != 16) {
			return;
		}

		offer(new AudioChunk(data.clone(), sampleRate, channels, frames));
	}

	/**
	 * @return the length of the chunk in microseconds.
	 */
	@Override
	long weight(AudioChunk chunk) {
		return chunk.sampleRate() > 0 ? chunk.frames() * 1_000_000L / chunk.sampleRate() : 0;
	}

	private static long micros(Duration duration) {
		long seconds = duration.getSeconds();

		return seconds >= Long.MAX_VALUE / 1_000_000 ? Long.MAX_VALUE
				: seconds * 1_000_000 + duration.getNano() / 1_000;
	}
}
