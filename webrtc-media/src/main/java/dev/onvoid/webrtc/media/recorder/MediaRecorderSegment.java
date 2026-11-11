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

package dev.onvoid.webrtc.media.recorder;

import java.nio.file.Path;
import java.time.Duration;

/**
 * A file a recording was written into, and finished: a segment of a {@link
 * MediaRecorder} that records in {@link SegmentOptions segments}, or a replay
 * saved by a {@link ReplayBuffer}. It begins with a key frame of every video
 * track and plays on its own.
 *
 * @author Alex Andres
 */
public final class MediaRecorderSegment {

	private final long index;

	private final Path file;

	private final Duration start;

	private final Duration duration;

	private final long size;

	private final MediaRecorderResult result;


	MediaRecorderSegment(long index, Path file, long startUs, long durationUs, long size,
			MediaRecorderResult result) {
		this.index = index;
		this.file = file;
		this.start = Duration.ofNanos(startUs * 1000);
		this.duration = Duration.ofNanos(durationUs * 1000);
		this.size = size;
		this.result = result;
	}

	/**
	 * Returns the number of the segment, which its file name holds, or of the
	 * replay, counting the replays a buffer saved from 0.
	 *
	 * @return The number.
	 */
	public long getIndex() {
		return index;
	}

	/**
	 * @return The file.
	 */
	public Path getFile() {
		return file;
	}

	/**
	 * Returns where the file begins in the recording, counted from when the
	 * recorder or buffer was started. A file's own timestamps begin at zero.
	 *
	 * @return The offset of the first frame.
	 */
	public Duration getStart() {
		return start;
	}

	/**
	 * @return The time from the first frame in the file to its last.
	 */
	public Duration getDuration() {
		return duration;
	}

	/**
	 * @return The size of the file once it was finished, in bytes.
	 */
	public long getSize() {
		return size;
	}

	/**
	 * Returns whether everything went into the file: {@link
	 * MediaRecorderResult#COMPLETE}, or {@link MediaRecorderResult#PARTIAL} if
	 * writing, finishing or closing it failed, and it ends early or lacks its
	 * index.
	 *
	 * @return The result of this file.
	 */
	public MediaRecorderResult getResult() {
		return result;
	}

	@Override
	public String toString() {
		return "MediaRecorderSegment[index=" + index + ", file=" + file + ", start=" + start + ", duration="
				+ duration + ", size=" + size + ", result=" + result + "]";
	}

}
