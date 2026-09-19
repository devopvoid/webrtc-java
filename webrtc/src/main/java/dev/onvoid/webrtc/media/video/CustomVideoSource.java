/*
 * Copyright 2019 Alex Andres
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

package dev.onvoid.webrtc.media.video;

import dev.onvoid.webrtc.media.SyncClock;

/**
 * Custom implementation of a video source for WebRTC that allows pushing video frames
 * from external sources directly to the WebRTC video pipeline.
 *
 * @author Alex Andres
 */
public class CustomVideoSource extends VideoTrackSource {

	/**
	 * Constructs a new CustomVideoSource instance.
	 */
	public CustomVideoSource() {
		super();

		initialize();
	}

	/**
	 * Constructs a new CustomVideoSource instance with a specified SyncClock.
	 *
	 * @param clock The SyncClock to use for timing and synchronization.
	 */
	public CustomVideoSource(SyncClock clock) {
		super();

		initializeWithClock(clock);
	}

	/**
	 * Pushes a video frame to be processed by this video source. The frame is
	 * treated as captured at the moment of the call.
	 *
	 * @param frame The video frame to be pushed to the source.
	 */
	public native void pushFrame(VideoFrame frame);

	/**
	 * Pushes a video frame that was captured, or is meant to be shown, at the
	 * given time.
	 * <p>
	 * The sent frame rate follows the capture timestamps, not the moments the
	 * frames are pushed, so a source that knows its own timing, such as one
	 * playing a video file, should use this. The receiver then sees the
	 * intended timing even when the pushing thread is late, and audio pushed
	 * with matching timestamps stays in sync with the video.
	 * <p>
	 * Frames must still be pushed in real time, because a frame is encoded and
	 * sent when it arrives. Timestamps must increase by at least one
	 * millisecond from frame to frame; WebRTC drops a frame whose capture time
	 * does not advance.
	 *
	 * @param frame       The video frame to be pushed to the source.
	 * @param timestampUs The capture time of the frame, on the clock of
	 *                    {@link dev.onvoid.webrtc.media.SyncClock#currentTimeUs()}.
	 *
	 * @see dev.onvoid.webrtc.media.SyncClock#currentTimeUs()
	 */
	public void pushFrame(VideoFrame frame, long timestampUs) {
		// Not a native method itself: two native methods of the same name
		// would have to carry JNI's mangled long names, which would rename the
		// existing entry point for no gain.
		pushFrameTimestamped(frame, timestampUs);
	}

	/**
	 * Disposes of any native resources held by this video source.
	 * This method should be called when the video source is no longer needed
	 * to prevent memory leaks.
	 */
	public native void dispose();

	/**
	 * Hands the frame to the native source, stamped with the given capture
	 * time.
	 *
	 * @param frame       The video frame to be pushed to the source.
	 * @param timestampUs The capture time of the frame in microseconds.
	 */
	private native void pushFrameTimestamped(VideoFrame frame, long timestampUs);

	/**
	 * Initializes the native resources required by this video source.
	 */
	private native void initialize();

	/**
	 * Initializes the native resources required by this video source with a specified SyncClock.
	 *
	 * @param clock The SyncClock to use for timing and synchronization.
	 */
	private native void initializeWithClock(SyncClock clock);

}
