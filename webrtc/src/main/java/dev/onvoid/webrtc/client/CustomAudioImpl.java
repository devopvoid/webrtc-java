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
 * into 10 ms chunks, which the runtime's audio pacer hands to the source one
 * every 10 ms. Its thread is the only one that pushes into the source, as
 * {@link CustomAudioSource} requires, and it runs no timeouts, which a slow
 * push would hold up.
 */
final class CustomAudioImpl extends LocalAudioImpl implements CustomAudio {

	private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

	/**
	 * How late a tick may run and still send. The pacer runs at a fixed rate,
	 * so after a stall it runs the missed ticks back to back: those up to this
	 * late are made up for, so that a short stall keeps the pace, and the
	 * earlier ones are skipped, so that a long one does not burst out all the
	 * audio of the pause at once. Skipped ticks leave the queue as it is.
	 */
	private static final long MAX_LATE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

	private final CustomAudioSource source;

	// Confined to the pacer, once the ticker is scheduled.

	/** When the first tick was due. */
	private long start;

	/** The ticks run so far. */
	private long ticks;

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
				start = rtc.clock.getAsLong();
				ticker = rtc.audioPacer.scheduleAtFixedRate(this::tick, 0, TICK_NANOS, TimeUnit.NANOSECONDS);
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
		long late = rtc.clock.getAsLong() - (start + ticks++ * TICK_NANOS);

		if (late > MAX_LATE_NANOS) {
			return;
		}

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
