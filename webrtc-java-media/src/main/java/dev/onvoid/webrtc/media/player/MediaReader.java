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

package dev.onvoid.webrtc.media.player;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * An opened media source, and what it contains.
 * <p>
 * A source is a media file, or a live stream behind an {@code rtsp://} URL,
 * such as an IP camera. Opening reads the container and picks the video and
 * audio stream that are meant to be played; a source with neither fails to
 * open.
 * <p>
 * Any single operation that waits on the source gives up once the reader's
 * timeout has passed: opening it, reading from it during playback, seeking in
 * it and closing it. For a file that is never an issue; for a stream it is
 * what keeps an unreachable or stalled server from blocking indefinitely.
 * <p>
 * A reader holds a native resource and has to be closed. It is not safe to use
 * from several threads at once, other than {@link #close()}, which may be
 * called more than once and from any thread.
 *
 * @author Alex Andres
 */
public class MediaReader implements AutoCloseable {

	static {
		FFmpeg.load();
	}

	/**
	 * How long an operation on the source may wait when no timeout is given.
	 */
	public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

	/** The native reader, or 0 once this reader has been closed. */
	private long handle;


	/**
	 * Opens the media file at the given path.
	 *
	 * @param path The path of the file to open.
	 *
	 * @throws IOException if the source cannot be opened, or holds nothing
	 *                     that can be played.
	 */
	public MediaReader(Path path) throws IOException {
		this(path.toAbsolutePath().toString());
	}

	/**
	 * Opens the given media source.
	 *
	 * @param source The path or {@code rtsp://} URL of the source to open.
	 *
	 * @throws IOException if the source cannot be opened, or holds nothing
	 *                     that can be played.
	 */
	public MediaReader(String source) throws IOException {
		this(source, DEFAULT_TIMEOUT);
	}

	/**
	 * Opens the given media source, allowing each operation on it the given
	 * time before it fails.
	 *
	 * @param source  The path or {@code rtsp://} URL of the source to open.
	 * @param timeout How long opening, and later any single read, seek or
	 *                close, may wait on the source. {@link Duration#ZERO}
	 *                waits as long as it takes.
	 *
	 * @throws IOException              if the source cannot be opened in
	 *                                  time, or holds nothing that can be
	 *                                  played.
	 * @throws IllegalArgumentException if the timeout is negative.
	 */
	public MediaReader(String source, Duration timeout) throws IOException {
		Objects.requireNonNull(timeout, "Timeout is null");

		if (timeout.isNegative()) {
			throw new IllegalArgumentException("Timeout is negative: " + timeout);
		}

		handle = open(source, toMicros(timeout));
	}

	/**
	 * Returns what this source contains: how long it runs, and the format of
	 * its video and audio streams.
	 *
	 * @return The media information.
	 *
	 * @throws IllegalStateException if this reader has been closed.
	 */
	public MediaInfo getInfo() {
		return info(handle);
	}

	/**
	 * Releases the native reader. Closing a reader that is already closed does
	 * nothing.
	 */
	@Override
	public synchronized void close() {
		long closing = handle;

		// Cleared first, so that a second close finds nothing to release even
		// if the first one is still running.
		handle = 0;

		dispose(closing);
	}

	/**
	 * Hands the native reader over to a caller that takes responsibility for
	 * releasing it, and leaves this reader closed. This is how a
	 * {@link MediaPlayer} adopts a reader: two owners of the same pointer
	 * would release it twice.
	 *
	 * @return The native reader, or {@code 0} if it was already given away or
	 *         closed.
	 */
	synchronized long detach() {
		long detaching = handle;

		handle = 0;

		return detaching;
	}

	private static long toMicros(Duration duration) {
		try {
			return duration.toNanos() / 1000;
		}
		catch (ArithmeticException e) {
			// Longer than nanoseconds can count, which is as good as never.
			return 0;
		}
	}

	private static native long open(String source, long timeoutUs) throws IOException;

	private static native MediaInfo info(long handle);

	private static native void dispose(long handle);

}
