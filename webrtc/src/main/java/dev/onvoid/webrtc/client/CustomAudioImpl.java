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

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;

/**
 * An audio track fed with audio the application pushes. Pushed audio is cut
 * into 10 ms chunks, of which one is due every 10 ms. The runtime's audio
 * pacer only counts what is due, for all tracks, and a thread of the audio
 * sender hands it to the source. At most one does so at a time for each track,
 * as {@link CustomAudioSource} requires, so that a slow push holds up this
 * track alone.
 */
final class CustomAudioImpl extends LocalAudioImpl implements CustomAudio {

	private static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

	/**
	 * How late a tick may run and still send. Ticks keep coming at a fixed
	 * rate, so after a stall of the pacer or of this track's sending the
	 * missed ones run back to back: those up to this late are made up for, so
	 * that a short stall keeps the pace, and the earlier ones are skipped, so
	 * that a long one does not burst out all the audio of the pause at once.
	 * Skipped ticks leave the queue as it is.
	 */
	private static final long MAX_LATE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

	private static final Duration CHUNK = Duration.ofNanos(TICK_NANOS);

	/** The queue limit by default, 10 seconds in chunks. */
	private static final int DEFAULT_MAX_CHUNKS = 1000;

	private final CustomAudioSource source;

	// Confined to the sending turn, once the ticker is scheduled.

	/** When the first tick was due. */
	private long start;

	/** The ticks run so far. */
	private long ticks;

	// Guarded by queue.

	private final ArrayDeque<Chunk> queue = new ArrayDeque<>();

	private PcmFormat format;

	private byte[] partial;

	private int partialLength;

	private int maxChunks = DEFAULT_MAX_CHUNKS;

	private AudioOverflow overflow = AudioOverflow.REJECT;

	private ScheduledFuture<?> ticker;

	private boolean stopped;

	/** The ticks the pacer counted that no sending turn has taken yet. */
	private long dueTicks;

	/** True while a sending turn runs or waits for its thread. */
	private boolean sending;


	CustomAudioImpl(LocalMediaImpl media, AudioTrack track, CustomAudioSource source) {
		super(media, track, source::dispose, false);

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

			boolean newFormat = !pcmFormat.equals(format);

			if (overflow == AudioOverflow.REJECT) {
				// Checked before anything is queued, so that a refused push
				// leaves no part of itself behind.
				long added = newFormat
						? (partialLength > 0 ? 1 : 0) + pcm.length / pcmFormat.bytesPer10Ms()
						: (partialLength + (long) pcm.length) / format.bytesPer10Ms();

				if (queue.size() + added > maxChunks) {
					throw new AudioQueueFullException("The queue holds " + queue.size() * 10L + " ms, and the "
							+ "audio would add " + added * 10 + " ms, beyond the limit of " + maxChunks * 10L + " ms");
				}
			}

			if (newFormat) {
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
					enqueue(new Chunk(partial, format));
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
	public void queueLimit(Duration max, AudioOverflow overflow) {
		requireNonNull(max, "max");
		requireNonNull(overflow, "overflow");

		if (max.compareTo(CHUNK) < 0) {
			throw new IllegalArgumentException("The limit must be at least 10 ms, got " + max);
		}

		synchronized (queue) {
			this.maxChunks = (int) Math.min(max.dividedBy(CHUNK), Integer.MAX_VALUE);
			this.overflow = overflow;

			dropOverflow();
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
			enqueue(new Chunk(partial, format));
		}

		partial = null;
		partialLength = 0;
	}

	/**
	 * Queues a chunk. With {@link AudioOverflow#DROP_OLDEST} the queue is kept
	 * within its limit chunk by chunk, so that even a long push holds no more
	 * than that; {@link AudioOverflow#REJECT} has checked the push before.
	 */
	private void enqueue(Chunk chunk) {
		queue.add(chunk);

		dropOverflow();
	}

	private void dropOverflow() {
		if (overflow == AudioOverflow.DROP_OLDEST) {
			while (queue.size() > maxChunks) {
				queue.poll();
			}
		}
	}

	/**
	 * Counts a tick, on the pacer, which never waits for the source: a turn
	 * that is busy takes the tick along, however long it takes.
	 */
	private void tick() {
		synchronized (queue) {
			dueTicks++;

			if (sending) {
				return;
			}

			sending = true;
		}

		rtc.audioSender.execute(this::send);
	}

	/** A sending turn: runs the ticks due, until none is left. */
	private void send() {
		while (takeTick()) {
			long late = rtc.clock.getAsLong() - (start + ticks++ * TICK_NANOS);

			if (late <= MAX_LATE_NANOS) {
				sendChunk();
			}
		}
	}

	/**
	 * @return true when a tick was due, false when the turn is over.
	 */
	private boolean takeTick() {
		synchronized (queue) {
			if (dueTicks == 0) {
				sending = false;
				return false;
			}

			dueTicks--;
			return true;
		}
	}

	private void sendChunk() {
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
