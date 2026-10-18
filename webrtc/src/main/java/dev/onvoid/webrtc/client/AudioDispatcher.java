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

import java.util.ArrayDeque;
import java.util.function.Consumer;

import dev.onvoid.webrtc.media.audio.AudioTrackSink;

/**
 * Hands the audio of a track to a handler on a thread of its own, in order.
 * When the handler falls more than {@link #CAPACITY} chunks behind, the oldest
 * waiting chunk is dropped.
 */
final class AudioDispatcher implements AudioTrackSink {

	/** Five seconds of 10 ms chunks. */
	private static final int CAPACITY = 500;

	private final Consumer<AudioChunk> handler;

	private final SerialExecutor executor;

	private final Consumer<Throwable> errorHandler;

	private final ArrayDeque<AudioChunk> queue = new ArrayDeque<>();

	private boolean scheduled;

	private boolean closed;


	AudioDispatcher(Consumer<AudioChunk> handler, SerialExecutor executor,
			Consumer<Throwable> errorHandler) {
		this.handler = handler;
		this.executor = executor;
		this.errorHandler = errorHandler;
	}

	@Override
	public void onData(byte[] data, int bitsPerSample, int sampleRate, int channels, int frames) {
		if (bitsPerSample != 16) {
			return;
		}

		AudioChunk chunk = new AudioChunk(data.clone(), sampleRate, channels, frames);

		synchronized (queue) {
			if (closed) {
				return;
			}

			queue.add(chunk);

			if (queue.size() > CAPACITY) {
				queue.poll();
			}
			if (!scheduled) {
				scheduled = true;
				executor.execute(this::drain);
			}
		}
	}

	void close() {
		synchronized (queue) {
			closed = true;
			queue.clear();
		}
	}

	private void drain() {
		while (true) {
			AudioChunk chunk;

			synchronized (queue) {
				chunk = queue.poll();

				if (chunk == null) {
					scheduled = false;
					return;
				}
			}

			try {
				handler.accept(chunk);
			}
			catch (Throwable e) {
				errorHandler.accept(e);
			}
		}
	}
}
