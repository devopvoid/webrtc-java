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

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;

/**
 * An audio track fed with audio the application pushes. Pushed audio is cut
 * into 10 ms chunks, which the runtime's timer thread hands to the source one
 * every 10 ms. That thread is the only one that pushes into the source, as
 * {@link CustomAudioSource} requires.
 */
final class CustomAudioImpl extends LocalAudioImpl implements CustomAudio {

	private final CustomAudioSource source;

	// Guarded by queue.

	private final ArrayDeque<Chunk> queue = new ArrayDeque<>();

	private PcmFormat format;

	private byte[] partial;

	private int partialLength;

	private ScheduledFuture<?> ticker;

	private boolean stopped;


	CustomAudioImpl(LocalMediaImpl media, AudioTrack track, CustomAudioSource source) {
		super(media, track, source::dispose);

		this.source = source;
	}

	@Override
	public void push(byte[] pcm, PcmFormat pcmFormat) {
		requireNonNull(pcm, "pcm");
		requireNonNull(pcmFormat, "format");

		int frameSize = pcmFormat.channels() * 2;

		if (pcm.length % frameSize != 0) {
			throw new IllegalArgumentException("The audio holds " + pcm.length + " bytes, which is no whole number of "
					+ frameSize + "-byte samples of " + pcmFormat);
		}

		synchronized (queue) {
			if (stopped) {
				return;
			}

			if (!pcmFormat.equals(format)) {
				flushPartial();
				format = pcmFormat;
				partial = new byte[pcmFormat.bytesPer10Ms()];
				partialLength = 0;
			}

			int offset = 0;

			while (offset < pcm.length) {
				int count = Math.min(partial.length - partialLength, pcm.length - offset);

				System.arraycopy(pcm, offset, partial, partialLength, count);
				partialLength += count;
				offset += count;

				if (partialLength == partial.length) {
					queue.add(new Chunk(partial, format));
					partial = new byte[format.bytesPer10Ms()];
					partialLength = 0;
				}
			}

			if (ticker == null) {
				ticker = rtc.timer.scheduleAtFixedRate(this::tick, 0, 10, TimeUnit.MILLISECONDS);
			}
		}
	}

	@Override
	public long queuedMillis() {
		synchronized (queue) {
			return queue.size() * 10L;
		}
	}

	@Override
	public void clear() {
		synchronized (queue) {
			queue.clear();
			partialLength = 0;
		}
	}

	@Override
	void beforeRelease() {
		synchronized (queue) {
			stopped = true;
			queue.clear();

			if (ticker != null) {
				ticker.cancel(false);
				ticker = null;
			}
		}
	}

	/** Queues what is left of the previous format, filled with silence. */
	private void flushPartial() {
		if (partialLength > 0) {
			// What lies beyond may be audio that clear() dropped.
			Arrays.fill(partial, partialLength, partial.length, (byte) 0);
			queue.add(new Chunk(partial, format));
		}

		partial = null;
		partialLength = 0;
	}

	private void tick() {
		Chunk chunk;

		synchronized (queue) {
			chunk = queue.poll();
		}

		if (chunk == null) {
			return;
		}

		synchronized (this) {
			if (!isLive()) {
				return;
			}

			try {
				source.pushAudio(chunk.data, 16, chunk.format.sampleRate(), chunk.format.channels(),
						chunk.format.framesPer10Ms());
			}
			catch (Throwable e) {
				rtc.reportError(e);
			}
		}
	}



	private static final class Chunk {

		final byte[] data;

		final PcmFormat format;


		Chunk(byte[] data, PcmFormat format) {
			this.data = data;
			this.format = format;
		}
	}
}
