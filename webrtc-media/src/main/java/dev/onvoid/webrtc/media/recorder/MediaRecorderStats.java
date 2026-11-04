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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What a {@link MediaRecorder} lost so far, and how close it came to that, as
 * {@link MediaRecorder#getStats()} tells. A recording can end as {@link
 * MediaRecorderResult#COMPLETE} with frames missing: the result speaks of
 * what the recorder took, and this of what it had to leave out before.
 * <p>
 * Frames that a track was never meant to record do not count, such as those
 * before its first key frame, those of another simulcast layer, or those that
 * arrive once the recording has failed.
 *
 * @author Alex Andres
 */
public final class MediaRecorderStats {

	/** How many values the native recorder reports per track. */
	private static final int TRACK_VALUES = 3;

	private final long maxQueuedBytes;

	private final List<Track> tracks;


	/**
	 * @param values The peak of the queue, then the counters of each track in
	 *               the order of the native MediaRecorder::TrackStats.
	 */
	MediaRecorderStats(long[] values) {
		List<Track> list = new ArrayList<>();

		for (int i = 1; i + TRACK_VALUES <= values.length; i += TRACK_VALUES) {
			list.add(new Track(values[i], values[i + 1], values[i + 2]));
		}

		this.maxQueuedBytes = values[0];
		this.tracks = Collections.unmodifiableList(list);
	}

	/**
	 * Returns the most that ever waited for the writer, of all tracks together,
	 * which share one queue of 64 MB. Near that limit, frames are dropped.
	 *
	 * @return The peak of the queue, in bytes.
	 */
	public long getMaxQueuedBytes() {
		return maxQueuedBytes;
	}

	/**
	 * @return What each track lost, in the order the tracks were added.
	 */
	public List<Track> getTracks() {
		return tracks;
	}


	/**
	 * What one track of a recording lost.
	 */
	public static final class Track {

		private final long droppedQueueFull;

		private final long droppedAllocationFailed;

		private final long droppedAwaitingKeyFrame;


		private Track(long droppedQueueFull, long droppedAllocationFailed, long droppedAwaitingKeyFrame) {
			this.droppedQueueFull = droppedQueueFull;
			this.droppedAllocationFailed = droppedAllocationFailed;
			this.droppedAwaitingKeyFrame = droppedAwaitingKeyFrame;
		}

		/**
		 * @return How many frames were dropped because the writer did not keep
		 * up and its queue was full.
		 */
		public long getDroppedQueueFull() {
			return droppedQueueFull;
		}

		/**
		 * @return How many frames were dropped because there was no memory to
		 * copy them.
		 */
		public long getDroppedAllocationFailed() {
			return droppedAllocationFailed;
		}

		/**
		 * Returns how many video frames were dropped on the way to the next
		 * key frame, since a decoder could do nothing with them after what
		 * was lost before. These frames make the visible gap in the video.
		 *
		 * @return The number of frames.
		 */
		public long getDroppedAwaitingKeyFrame() {
			return droppedAwaitingKeyFrame;
		}

		/**
		 * @return How many frames were dropped for any of these reasons.
		 */
		public long getDroppedFrames() {
			return droppedQueueFull + droppedAllocationFailed + droppedAwaitingKeyFrame;
		}

	}

}
